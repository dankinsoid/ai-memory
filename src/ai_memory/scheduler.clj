(ns ai-memory.scheduler
  "Periodic background tasks. Uses JDK ScheduledExecutorService."
  (:require [ai-memory.store.protocols :as p]
            [clojure.tools.logging :as log])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]
           [java.time LocalTime Duration]))

(defn- ms-until-hour
  "Milliseconds from now until next occurrence of `hour` (0-23)."
  [hour]
  (let [now    (LocalTime/now)
        target (LocalTime/of hour 0)
        dur    (Duration/between now target)]
    (if (neg? (.toMillis dur))
      (+ (.toMillis dur) 86400000)
      (.toMillis dur))))

(defn start
  "Starts scheduler. Returns the ScheduledExecutorService (for shutdown)."
  [fact-store]
  (let [^ScheduledExecutorService executor (Executors/newSingleThreadScheduledExecutor)]
    ;; Reconcile tag counts daily at 3 AM
    (.scheduleAtFixedRate executor
      (fn []
        (try
          (let [start (System/nanoTime)]
            (p/reconcile-tag-counts! fact-store)
            (log/info "Tag count reconciliation completed in"
                      (/ (double (- (System/nanoTime) start)) 1e6) "ms"))
          (catch Exception e
            (log/error e "Tag count reconciliation failed"))))
      (ms-until-hour 3)
      86400000
      TimeUnit/MILLISECONDS)
    (log/info "Scheduler started — tag reconciliation daily at 03:00")
    executor))

(defn stop [^ScheduledExecutorService executor]
  (when executor
    (.shutdown executor)
    (log/info "Scheduler stopped")))
