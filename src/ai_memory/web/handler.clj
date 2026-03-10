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

(defn app
  "Builds Ring handler. `ctx` is the service context (stores + blob-path + metrics),
   `cfg` is the raw config (for auth, MCP base-url)."
  [ctx cfg]
  (let [handler (ring/ring-handler
                  (ring/router
                    [["/" {:get (fn [_] (-> (resp/resource-response "public/index.html")
                                         (resp/content-type "text/html")))}]
                     ["/api" {:middleware [wrap-normalize-keys]}
                      ["/health" {:get (fn [req] (api/get-health ctx req))}]
                      ["/diagnostics" {:get (fn [req] (api/get-diagnostics ctx req))}]
                      ["/stats" {:get (fn [req] (api/get-stats ctx req))}]
                      ["/graph" {:get (fn [req] (api/get-graph ctx req))}]
                      ["/graph/top-nodes" {:get (fn [req] (api/get-top-nodes ctx req))}]
                      ["/graph/neighborhood" {:get (fn [req] (api/get-graph-neighborhood ctx req))}]
                      ["/facts/:id" {:get    (fn [req] (api/get-fact-detail ctx req))
                                     :patch  (fn [req] (api/update-fact ctx req))
                                     :delete (fn [req] (api/delete-fact ctx req))}]
                      ["/admin/reindex" {:post (fn [req] (api/reindex-vectors ctx req))}]
                      ;; export/import moved to /api/admin/export and /import
                      ;; as binary routes (outside muuntaja) — see below
                      ["/admin/promote-eternal" {:post (fn [req] (api/promote-eternal ctx req))}]
                      ["/nodes" {:get  (fn [req] (api/list-nodes req))
                                 :post (fn [req] (api/create-node ctx req))}]
                      ["/remember" {:post (fn [req] (api/remember ctx req))}]
                      ["/reinforce" {:post (fn [req] (api/reinforce ctx req))}]
                      ["/recall" {:post (fn [req] (api/recall ctx req))}]
                      ["/tags" {:get  (fn [req] (api/browse-tags ctx req))}]
                      ["/tags/count" {:post (fn [req] (api/count-facts ctx req))}]
                      ["/tags/facts" {:post (fn [req] (api/get-facts ctx req))}]
                      ["/tags/resolve" {:post (fn [req] (api/resolve-tags ctx req))}]
                      ["/blobs" {:get (fn [req] (api/list-blobs ctx req))}]
                      ["/blobs/read" {:post (fn [req] (api/read-blob ctx req))}]
                      ["/blobs/exec" {:post (fn [req] (api/exec-blob ctx req))}]
                      ["/session/sync" {:post (fn [req] (api/session-sync ctx req))}]
                      ["/session/continue" {:post (fn [req] (api/session-continue ctx req))}]
                      ["/session/chain" {:post (fn [req] (api/session-chain ctx req))}]
                      ["/session" {:post (fn [req] (api/session-update ctx req))}]
                      ["/project" {:post (fn [req] (api/project-update ctx req))}]]
                     ;; Binary routes — no muuntaja (ZIP I/O)
                     ["/api/admin/export" {:get  (fn [req] (api/export-snapshot ctx req))
                                           :middleware [parameters/parameters-middleware]}]
                     ["/api/admin/import" {:post (fn [req] (api/import-snapshot ctx req))
                                           :middleware [parameters/parameters-middleware]}]
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
      (:metrics ctx)     (ring-collector/wrap-metrics (:metrics ctx) {:path "/metrics"})
      (:api-token cfg)   (wrap-bearer-auth (:api-token cfg)))))

(defn start
  "Starts Jetty web server.
   `ctx`  — service context
   `cfg`  — raw config (port, api-token)
   `conn` — Datomic conn (unused, kept for compatibility)"
  [{:keys [port ctx cfg]}]
  (jetty/run-jetty (app ctx cfg) {:port port :join? false}))
