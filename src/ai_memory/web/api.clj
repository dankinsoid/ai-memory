(ns ai-memory.web.api
  (:require [ai-memory.db.core :as db]
            [ai-memory.graph.node :as node]))

(defn get-graph
  "Returns a subgraph for visualization (nodes + edges)."
  [conn _req]
  ;; TODO: return paginated/filtered subgraph
  {:status 200
   :body   {:nodes [] :edges []}})

(defn list-nodes [conn req]
  (let [db       (db/db conn)
        node-type (get-in req [:query-params "type"])]
    {:status 200
     :body   (if node-type
               (node/find-by-type db node-type)
               [])}))

(defn create-node [conn req]
  (let [body (:body-params req)]
    (node/create-node conn body)
    {:status 201
     :body   {:status "created"}}))

(defn recall [conn req]
  ;; TODO: integrate with traverse/recall
  {:status 200
   :body   {:results []}})
