(ns ai-memory.web.api
  (:require [ai-memory.db.core :as db]
            [ai-memory.graph.node :as node]
            [ai-memory.graph.edge :as edge]
            [ai-memory.graph.write :as write]
            [datomic.api :as d]))

(defn- node->d3 [n]
  {:id      (str (:node/id n))
   :content (:node/content n)
   :type    (some-> (:node/type n) :db/ident name)
   :weight  (:node/weight n)
   :tags    (:node/tags n)})

(defn- edge->d3 [e]
  {:source (str (get-in e [:edge/from :node/id]))
   :target (str (get-in e [:edge/to :node/id]))
   :weight (:edge/weight e)})

(defn get-graph
  "Returns full graph for D3 visualization."
  [conn _req]
  (let [db    (db/db conn)
        nodes (d/q '[:find [(pull ?e [* {:node/type [:db/ident]}]) ...]
                      :where [?e :node/id]]
                    db)
        edges (edge/find-all db)]
    {:status 200
     :body   {:nodes (mapv node->d3 nodes)
              :links (mapv edge->d3 edges)}}))

(defn list-nodes [conn req]
  (let [db       (db/db conn)
        node-type (get-in req [:query-params "type"])]
    {:status 200
     :body   (if node-type
               (node/find-by-type db node-type)
               [])}))

(defn create-node [conn cfg req]
  (let [body (:body-params req)]
    (node/create-node conn cfg body)
    {:status 201
     :body   {:status "created"}}))

(defn remember [conn cfg req]
  (let [body (:body-params req)
        result (write/remember conn cfg body)]
    {:status 201
     :body   result}))

(defn recall [conn cfg req]
  ;; TODO: integrate with traverse/recall
  {:status 200
   :body   {:results []}})
