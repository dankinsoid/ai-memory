(ns ai-memory.graph.delete
  (:require [datalevin.core :as d]
            [ai-memory.db.core :as db]
            [ai-memory.embedding.local-vector-store :as lvs]
            [ai-memory.blob.store :as blob-store]
            [clojure.tools.logging :as log]))

(defn- dec-tag-counts!
  "Client-side tag count decrements for given tag names."
  [conn tag-names]
  (when (seq tag-names)
    (let [db (d/db conn)]
      (doseq [tag-name tag-names]
        (let [current (or (:tag/node-count (d/pull db [:tag/node-count] [:tag/name tag-name])) 0)]
          (d/transact! conn [[:db/add [:tag/name tag-name] :tag/node-count (max 0 (dec current))]]))))))

(defn delete-node!
  "True deletion: retracts node + its edges from Datalevin, removes vectors, deletes blob dir.
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
                         (conj [:db/retractEntity eid]))]
      (lvs/delete-node-vecs! conn eid)
      (db/transact! conn tx-data)
      (dec-tag-counts! conn tag-names)
      (when blob-dir
        (blob-store/delete-blob-dir! (:blob-path cfg) blob-dir))
      {:deleted-id eid :blob-dir blob-dir})))

(defn reset-all!
  "Deletes all nodes, edges, resets tag counts to 0, wipes vectors, deletes all blob dirs.
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
    (lvs/delete-all-vecs! conn)
    (when (seq tx-data)
      (db/transact! conn tx-data))
    (let [deleted-blobs (blob-store/delete-all-blobs! (:blob-path cfg))]
      {:deleted-nodes (count node-eids)
       :deleted-edges (count edge-eids)
       :deleted-blobs deleted-blobs})))
