(ns ai-memory.web.api
  (:require [ai-memory.db.core :as db]
            [ai-memory.graph.node :as node]
            [ai-memory.graph.edge :as edge]
            [ai-memory.graph.write :as write]
            [ai-memory.mcp.server :as mcp-server]
            [ai-memory.tag.core :as tag]
            [ai-memory.tag.query :as tag-query]
            [datomic.api :as d]))

(defn- node->d3 [n]
  {:id      (str (:node/id n))
   :content (:node/content n)
   :type    (some-> (:node/type n) :db/ident name)
   :weight  (:node/weight n)
   :tags    (mapv :tag/path (:node/tag-refs n))})

(defn- edge->d3 [e]
  {:source (str (get-in e [:edge/from :node/id]))
   :target (str (get-in e [:edge/to :node/id]))
   :weight (:edge/weight e)})

(defn get-graph
  "Returns full graph for D3 visualization."
  [conn _req]
  (let [db    (db/db conn)
        nodes (d/q '[:find [(pull ?e [* {:node/type [:db/ident]}
                                          {:node/tag-refs [:tag/path]}]) ...]
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

(defn browse-tags [conn _cfg req]
  (let [db    (db/db conn)
        path  (get-in req [:query-params "path"])
        depth (some-> (get-in req [:query-params "depth"]) parse-long)]
    {:status 200
     :body   (if depth
               (tag-query/taxonomy db path depth)
               (tag-query/browse db path))}))

(defn create-tag [conn _cfg req]
  (let [{:keys [name parent-path]} (:body-params req)
        path (tag/create-tag! conn {:name name :parent-path parent-path})]
    {:status 201
     :body   {:tag/path path}}))

(defn count-facts [conn cfg req]
  (let [db       (db/db conn)
        tag-sets (get-in req [:body-params :tag-sets])]
    {:status 200
     :body   {:counts (tag-query/count-by-tag-sets db (:metrics cfg) tag-sets)}}))

(defn get-facts [conn cfg req]
  (let [db       (db/db conn)
        body     (:body-params req)
        tag-sets (:tag-sets body)
        limit    (:limit body 50)]
    {:status 200
     :body   {:results (tag-query/fetch-by-tag-sets db (:metrics cfg) tag-sets {:limit limit})}}))

(defn recall [conn _cfg req]
  (let [db   (db/db conn)
        body (:body-params req)
        tags (:tags body)]
    {:status 200
     :body   {:results (if (seq tags)
                         (tag-query/by-tags db {:tags tags})
                         [])}}))

(defn session-sync [conn cfg req]
  (let [body   (:body-params req)
        params {:session-id (:session_id body)
                :cwd        (:cwd body)
                :messages   (:messages body)}]
    (if (:session-id params)
      {:status 200
       :body   (mcp-server/handle-session-sync conn cfg params)}
      {:status 400
       :body   {:error "session_id required"}})))
