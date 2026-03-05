(ns ai-memory.graph.delete
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.blob.store :as blob-store]
            [clojure.tools.logging :as log]))

(defn delete-node!
  "True deletion: retracts node + its edges from FactStore, removes vector, deletes blob dir.
   Returns {:deleted-id eid :blob-dir dir-name}. Throws if not found."
  [fact-store vector-store blob-path eid]
  (let [node (p/find-node fact-store eid)]
    (when-not (:node/content node)
      (throw (ex-info "Node not found" {:eid eid})))
    (let [blob-dir (:node/blob-dir node)]
      (p/delete-node! fact-store eid)
      (try (p/delete! vector-store eid)
           (catch Exception e (log/warn e "Failed to delete vector point" eid)))
      (when blob-dir
        (let [resolved (or (blob-store/resolve-blob-dir blob-path blob-dir)
                           blob-dir)]
          (blob-store/delete-blob-dir! blob-path resolved)))
      {:deleted-id eid :blob-dir blob-dir})))

(defn reset-all!
  "Deletes all nodes, edges, resets tag counts to 0, wipes vector store, deletes all blob dirs.
   Returns {:deleted-nodes N :deleted-edges M :deleted-blobs B}."
  [fact-store vector-store blob-path]
  (let [node-eids (p/all-nodes fact-store)
        edge-eids (mapv :edge/id (p/all-edges fact-store))]
    (p/reset-nodes! fact-store)
    (p/delete-all! vector-store)
    (let [deleted-blobs (blob-store/delete-all-blobs! blob-path)]
      {:deleted-nodes (count node-eids)
       :deleted-edges (count edge-eids)
       :deleted-blobs deleted-blobs})))
