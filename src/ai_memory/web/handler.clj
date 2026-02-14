(ns ai-memory.web.handler
  (:require [ring.adapter.jetty :as jetty]
            [reitit.ring :as ring]
            [muuntaja.core :as m]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [ai-memory.web.api :as api]))

(defn app [conn]
  (ring/ring-handler
    (ring/router
      [["/api"
        ["/health" {:get (fn [_] {:status 200 :body {:status "ok"}})}]
        ["/graph" {:get (fn [req] (api/get-graph conn req))}]
        ["/nodes" {:get  (fn [req] (api/list-nodes conn req))
                   :post (fn [req] (api/create-node conn req))}]
        ["/recall" {:post (fn [req] (api/recall conn req))}]]]
      {:data {:muuntaja   m/instance
              :middleware [muuntaja/format-middleware]}})
    (ring/routes
      (ring/create-resource-handler {:path "/"})
      (ring/create-default-handler))))

(defn start [{:keys [port conn]}]
  (jetty/run-jetty (app conn) {:port port :join? false}))
