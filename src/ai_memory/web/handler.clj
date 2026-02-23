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

(defn app [conn cfg]
  (let [handler (ring/ring-handler
                  (ring/router
                    [["/" {:get (fn [_] (-> (resp/resource-response "public/index.html")
                                         (resp/content-type "text/html")))}]
                     ["/api" {:middleware [wrap-normalize-keys]}
                      ["/health" {:get (fn [_] {:status 200 :body {:status "ok"}})}]
                      ["/stats" {:get (fn [req] (api/get-stats conn req))}]
                      ["/graph" {:get (fn [req] (api/get-graph conn req))}]
                      ["/graph/top-nodes" {:get (fn [req] (api/get-top-nodes conn cfg req))}]
                      ["/graph/neighborhood" {:get (fn [req] (api/get-graph-neighborhood conn cfg req))}]
                      ["/facts/:id" {:get (fn [req] (api/get-fact-detail conn cfg req))}]
                      ["/nodes" {:get  (fn [req] (api/list-nodes conn req))
                                 :post (fn [req] (api/create-node conn cfg req))}]
                      ["/remember" {:post (fn [req] (api/remember conn cfg req))}]
                      ["/reinforce" {:post (fn [req] (api/reinforce conn cfg req))}]
                      ["/recall" {:post (fn [req] (api/recall conn cfg req))}]
                      ["/tags" {:get  (fn [req] (api/browse-tags conn cfg req))}]
                      ["/tags/count" {:post (fn [req] (api/count-facts conn cfg req))}]
                      ["/tags/facts" {:post (fn [req] (api/get-facts conn cfg req))}]
                      ["/blobs" {:get (fn [req] (api/list-blobs conn cfg req))}]
                      ["/blobs/read" {:post (fn [req] (api/read-blob conn cfg req))}]
                      ["/blobs/exec" {:post (fn [req] (api/exec-blob conn cfg req))}]
                      ["/blobs/file" {:post (fn [req] (api/store-file conn cfg req))}]
                      ["/session/sync" {:post (fn [req] (api/session-sync conn cfg req))}]
                      ["/session/continue" {:post (fn [req] (api/session-continue conn cfg req))}]
                      ["/session/chain" {:post (fn [req] (api/session-chain conn cfg req))}]
                      ["/session" {:post (fn [req] (api/session-update conn cfg req))}]]
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

(defn start [{:keys [port conn cfg]}]
  (jetty/run-jetty (app conn cfg) {:port port :join? false}))
