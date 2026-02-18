(ns ai-memory.web.handler
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [iapetos.collector.ring :as ring-collector]
            [ai-memory.web.api :as api]))

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
                      ["/session/sync" {:post (fn [req] (api/session-sync conn cfg req))}]]]
                    {:data {:muuntaja   m/instance
                            :middleware [muuntaja/format-middleware]}})
                  (ring/routes
                    (ring/create-resource-handler {:path "/"})
                    (ring/create-default-handler)))]
    (if-let [registry (:metrics cfg)]
      (ring-collector/wrap-metrics handler registry {:path "/metrics"})
      handler)))

(defn start [{:keys [port conn cfg]}]
  (jetty/run-jetty (app conn cfg) {:port port :join? false}))
