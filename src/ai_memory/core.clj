;; @ai-generated(solo)
(ns ai-memory.core
  (:require [ai-memory.system :as system]
            [integrant.core :as ig]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main [& _args]
  (let [config (system/read-config)
        sys    (ig/init config)]
    (.addShutdownHook (Runtime/getRuntime)
      (Thread. ^Runnable (fn []
                           (log/info "Shutting down...")
                           (ig/halt! sys))))
    (log/info "ai-memory started")
    @(promise)))
