(ns ai-memory.graph.delete
  (:require [datomic.api :as d]
            [ai-memory.db.core :as db]
            [ai-memory.embedding.vector-store :as vs]
            [ai-memory.blob.store :as blob-store]
            [clojure.tools.logging :as log]))

(defn delete-node!
  "True deletion: retracts node + its edges from Datomic, removes Qdrant vector, deletes blob dir.
   Returns {:deleted-id eid :blob-dir dir-name}. Throws if not found."
  [conn cfg eid]
  (let [db   (d/db conn)
        node (d/pull db [:node/content :node/blob-dir {:node/tag-refs [:tag/name]}] eid)]
    (when-not (:node/content node)
      (throw (ex-info "Node not found" {:eid eid})))
    (let [blob-dir   (:node/blob-dir node)
          tag-names  (mapv :tag/name (:node/tag-refs node))
          edges-from (d/q '[:find [?e ...] :in $ ?n :where [?e :edge/from ?n]] db eid)
          edges-to   (d/q '[:find [?e ...] :in $ ?n :where [?e :edge/to   ?n]] db eid)
          edge-eids  (distinct (concat edges-from edges-to))
          tx-data    (-> []
                         (into (map #(vector :db/retractEntity %) edge-eids))
                         (into (map #(vector :fn/inc-tag-count % -1) tag-names))
                         (conj [:db/retractEntity eid]))]
      (db/transact! conn tx-data)
      (try (vs/delete-point! (:qdrant-url cfg) eid)
           (catch Exception e (log/warn e "Failed to delete Qdrant point" eid)))
      (when blob-dir
        (let [resolved (or (blob-store/resolve-blob-dir (:blob-path cfg) blob-dir)
                           blob-dir)]
          (blob-store/delete-blob-dir! (:blob-path cfg) resolved)))
      {:deleted-id eid :blob-dir blob-dir})))

(defn reset-all!
  "Deletes all nodes, edges, resets tag counts to 0, wipes Qdrant, deletes all blob dirs.
   Returns {:deleted-nodes N :deleted-edges M :deleted-blobs B}."
  [conn cfg]
  (let [db        (d/db conn)
        node-eids (d/q '[:find [?e ...] :where [?e :node/content]] db)
        edge-eids (d/q '[:find [?e ...] :where [?e :edge/id]] db)
        tag-names (d/q '[:find [?name ...] :where [?t :tag/name ?name]] db)
        tx-data   (-> []
                      (into (map #(vector :db/retractEntity %) edge-eids))
                      (into (map #(vector :db/retractEntity %) node-eids))
                      (into (map #(vector :db/add [:tag/name %] :tag/node-count 0) tag-names)))]
    (when (seq tx-data)
      (db/transact! conn tx-data))
    (vs/delete-all-points! (:qdrant-url cfg))
    (let [deleted-blobs (blob-store/delete-all-blobs! (:blob-path cfg))]
      {:deleted-nodes (count node-eids)
       :deleted-edges (count edge-eids)
       :deleted-blobs deleted-blobs})))
