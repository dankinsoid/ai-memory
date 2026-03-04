(ns ai-memory.web.handler
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [iapetos.collector.ring :as ring-collector]
            [clojure.walk :as walk]
            [clojure.string :as str]
            [ai-memory.web.api :as api]
            [ai-memory.web.mcp :as mcp]
            [ring.util.response :as resp]))

(defn- normalize-key [k]
  (if (keyword? k)
    (let [n (str/replace (name k) "_" "-")]
      (if-let [ns (namespace k)]
        (keyword ns n)
        (keyword n)))
    k))

(def wrap-normalize-keys
  "Middleware: converts underscore keys to dashes in :body-params."
  {:name ::normalize-keys
   :wrap (fn [handler]
           (fn [req]
             (if-let [bp (:body-params req)]
               (handler (assoc req :body-params
                          (walk/postwalk
                            (fn [x]
                              (if (map? x)
                                (into {} (map (fn [[k v]] [(normalize-key k) v])) x)
                                x))
                            bp)))
               (handler req))))})

(defn wrap-bearer-auth
  "Middleware: checks Authorization: Bearer <token>.
   Skips auth for /api/health and /metrics.
   When api-token is nil/blank, auth is disabled."
  [handler api-token]
  (if (str/blank? api-token)
    handler
    (fn [req]
      (let [path (:uri req)]
        (if (or (= path "/api/health")
                (not (or (str/starts-with? path "/api/")
                         (str/starts-with? path "/mcp/"))))
          (handler req)
          (let [auth-header (get-in req [:headers "authorization"])
                bearer      (when auth-header
                              (second (re-matches #"Bearer\s+(.*)" auth-header)))
                query-token (some->> (:query-string req)
                                    (re-find #"(?:^|&)token=([^&]+)")
                                    second)
                token       (or bearer query-token)]
            (if (= token api-token)
              (handler req)
              {:status  401
               :headers {"Content-Type" "application/json"}
               :body    "{\"error\":\"Unauthorized\"}"})))))))

(defn app [conn cfg stores]
  (let [handler (ring/ring-handler
                  (ring/router
                    [["/" {:get (fn [_] (-> (resp/resource-response "public/index.html")
                                         (resp/content-type "text/html")))}]
                     ["/api" {:middleware [wrap-normalize-keys]}
                      ["/health" {:get (fn [req] (api/get-health stores req))}]
                      ["/diagnostics" {:get (fn [req] (api/get-diagnostics stores req))}]
                      ["/stats" {:get (fn [req] (api/get-stats conn stores req))}]
                      ["/graph" {:get (fn [req] (api/get-graph conn stores req))}]
                      ["/graph/top-nodes" {:get (fn [req] (api/get-top-nodes stores req))}]
                      ["/graph/neighborhood" {:get (fn [req] (api/get-graph-neighborhood conn stores req))}]
                      ["/facts/:id" {:get    (fn [req] (api/get-fact-detail stores req))
                                     :patch  (fn [req] (api/update-fact stores req))
                                     :delete (fn [req] (api/delete-fact stores cfg req))}]
                      ["/admin/reset" {:post (fn [req] (api/reset-db stores cfg req))}]
                      ["/admin/reindex" {:post (fn [req] (api/reindex-vectors stores cfg req))}]
                      ["/admin/promote-eternal" {:post (fn [req] (api/promote-eternal stores req))}]
                      ["/nodes" {:get  (fn [req] (api/list-nodes req))
                                 :post (fn [req] (api/create-node stores req))}]
                      ["/remember" {:post (fn [req] (api/remember stores cfg req))}]
                      ["/reinforce" {:post (fn [req] (api/reinforce stores cfg req))}]
                      ["/recall" {:post (fn [req] (api/recall stores req))}]
                      ["/tags" {:get  (fn [req] (api/browse-tags stores req))}]
                      ["/tags/count" {:post (fn [req] (api/count-facts stores cfg req))}]
                      ["/tags/facts" {:post (fn [req] (api/get-facts stores cfg req))}]
                      ["/blobs" {:get (fn [req] (api/list-blobs stores req))}]
                      ["/blobs/read" {:post (fn [req] (api/read-blob cfg req))}]
                      ["/blobs/exec" {:post (fn [req] (api/exec-blob cfg req))}]
                      ["/blobs/file" {:post (fn [req] (api/store-file stores cfg req))}]
                      ["/session/sync" {:post (fn [req] (api/session-sync conn stores cfg req))}]
                      ["/session/continue" {:post (fn [req] (api/session-continue conn stores cfg req))}]
                      ["/session/chain" {:post (fn [req] (api/session-chain conn stores req))}]
                      ["/session" {:post (fn [req] (api/session-update conn stores cfg req))}]
                      ["/project" {:post (fn [req] (api/project-update stores cfg req))}]]
                     ["/mcp" {:handler (mcp/streamable-handler
                                          {:base-url  (str "http://localhost:" (:port cfg))
                                           :api-token (:api-token cfg)})}]]
                    {:data {:muuntaja   m/instance
                            :middleware [parameters/parameters-middleware
                                        muuntaja/format-middleware]}})
                  (ring/routes
                    (ring/create-resource-handler {:path "/"})
                    (ring/create-default-handler)))]
    (cond-> handler
      (:metrics cfg)    (ring-collector/wrap-metrics (:metrics cfg) {:path "/metrics"})
      (:api-token cfg)  (wrap-bearer-auth (:api-token cfg)))))

(defn start [{:keys [port conn cfg stores]}]
  (jetty/run-jetty (app conn cfg stores) {:port port :join? false}))
