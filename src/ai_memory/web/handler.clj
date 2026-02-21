(ns ai-memory.web.handler
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [iapetos.collector.ring :as ring-collector]
            [clojure.walk :as walk]
            [clojure.string :as str]
            [ai-memory.web.api :as api]))

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

(defn app [conn cfg]
  (let [handler (ring/ring-handler
                  (ring/router
                    [["/api"
                      ["/health" {:get (fn [_] {:status 200 :body {:status "ok"}})}]
                      ["/graph" {:get (fn [req] (api/get-graph conn req))}]
                      ["/nodes" {:get  (fn [req] (api/list-nodes conn req))
                                 :post (fn [req] (api/create-node conn cfg req))}]
                      ["/remember" {:post (fn [req] (api/remember conn cfg req))}]
                      ["/recall" {:post (fn [req] (api/recall conn cfg req))}]
                      ["/tags" {:get  (fn [req] (api/browse-tags conn cfg req))
                                :post (fn [req] (api/create-tag conn cfg req))}]
                      ["/tags/count" {:post (fn [req] (api/count-facts conn cfg req))}]
                      ["/tags/facts" {:post (fn [req] (api/get-facts conn cfg req))}]
                      ["/search" {:post (fn [req] (api/search conn cfg req))}]
                      ["/blobs" {:get (fn [req] (api/list-blobs conn cfg req))}]
                      ["/blobs/read" {:post (fn [req] (api/read-blob conn cfg req))}]
                      ["/blobs/file" {:post (fn [req] (api/store-file conn cfg req))}]
                      ["/session/sync" {:post (fn [req] (api/session-sync conn cfg req))}]
                      ["/session" {:post (fn [req] (api/session-update conn cfg req))}]]]
                    {:data {:muuntaja   m/instance
                            :middleware [parameters/parameters-middleware
                                    muuntaja/format-middleware
                                    wrap-normalize-keys]}})
                  (ring/routes
                    (ring/create-resource-handler {:path "/"})
                    (ring/create-default-handler)))]
    (if-let [registry (:metrics cfg)]
      (ring-collector/wrap-metrics handler registry {:path "/metrics"})
      handler)))

(defn start [{:keys [port conn cfg]}]
  (jetty/run-jetty (app conn cfg) {:port port :join? false}))
