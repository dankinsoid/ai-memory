;; @ai-generated(guided)
(ns ai-memory.service.admin
  "Admin operations: health checks, diagnostics, stats, reindexing, reset."
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.graph.node :as node]
            [ai-memory.graph.delete :as delete]
            [ai-memory.service.tags :as tags]))

(defn health
  "Health check with vector store reachability.
   `stores` — map with :vector-store
   Returns {:status 'ok' :qdrant {...}}."
  [stores]
  (let [info (p/store-info (:vector-store stores))]
    {:status "ok"
     :qdrant (select-keys info [:reachable? :status])}))

(defn diagnostics
  "Full diagnostic: collection info + optional test search.
   `stores`     — map with :vector-store, :embedding
   `test-query` — optional text to run end-to-end embedding + search"
  [stores test-query]
  (let [info (p/store-info (:vector-store stores))]
    (cond-> {:qdrant info}
      (and (:reachable? info) test-query)
      (assoc :test-search
             (try
               (let [vec     (p/embed-query (:embedding stores) test-query)
                     results (p/search (:vector-store stores) vec 3 nil)]
                 {:ok true :hits (count results)})
               (catch Exception e
                 {:ok false :error (.getMessage e)}))))))

(defn stats
  "Global counts for the stat bar.
   `stores` — map with :fact-store
   Returns {:fact-count N :tag-count N :edge-count N :tick N}."
  [stores]
  (let [fs (:fact-store stores)]
    {:fact-count (count (p/all-nodes fs))
     :tag-count  (count (p/all-tags fs))
     :edge-count (count (p/all-edges fs))
     :tick       (p/current-tick fs)}))

(defn reindex!
  "Re-embeds all nodes and tags into vector stores.
   `stores`    — map with :fact-store, :vector-store, :tag-vector-store, :embedding
   `blob-path` — filesystem base path for blobs
   Returns merged result from node and tag reindexing."
  [stores blob-path]
  (let [node-result (node/reindex-all! (:fact-store stores)
                                        (:vector-store stores)
                                        (:embedding stores)
                                        blob-path)
        tag-result  (tags/seed! stores)]
    (merge node-result tag-result)))

(defn reset-all!
  "Wipes all facts, edges, blobs, and vectors.
   `stores`    — map with :fact-store, :vector-store
   `blob-path` — filesystem base path
   Returns {:deleted-nodes N :deleted-edges M :deleted-blobs B}."
  [stores blob-path]
  (delete/reset-all! (:fact-store stores) (:vector-store stores) blob-path))
