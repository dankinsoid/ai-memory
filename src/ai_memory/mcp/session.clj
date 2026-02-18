(ns ai-memory.mcp.session
  "Turn summary buffer in RAM. Accumulates per-session summaries with timestamps.
   Consumed by session sync endpoint when matching summaries to blob sections."
  (:import [java.time Instant Duration]))

(def ^:private session-buffer (atom {}))
;; {session-id {:entries [{:summary "..." :timestamp #inst "..."}]
;;              :last-access #inst "..."}}

(def ^:private ttl-seconds 14400) ;; 4 hours

(defn- now ^Instant [] (Instant/now))

(defn- expired? [entry]
  (let [last-access ^Instant (:last-access entry)
        elapsed (.getSeconds (Duration/between last-access (now)))]
    (> elapsed ttl-seconds)))

(defn- evict-expired! []
  (swap! session-buffer
    (fn [m]
      (persistent!
        (reduce-kv (fn [acc k v]
                     (if (expired? v) acc (assoc! acc k v)))
                   (transient {}) m)))))

(defn append-turn-summary!
  "Appends a turn summary with current timestamp to the session buffer."
  [session-id summary]
  (evict-expired!)
  (swap! session-buffer
    (fn [m]
      (let [entry (get m session-id {:entries []})
            entry (-> entry
                      (update :entries conj {:summary summary :timestamp (now)})
                      (assoc :last-access (now)))]
        (assoc m session-id entry)))))

(defn get-turn-summaries
  "Returns accumulated turn summary entries for a session."
  [session-id]
  (get-in @session-buffer [session-id :entries] []))

(defn consume-turn-summaries!
  "Returns and clears all turn summaries for a session. Atomic."
  [session-id]
  (let [result (atom nil)]
    (swap! session-buffer
      (fn [m]
        (reset! result (get-in m [session-id :entries] []))
        (if (contains? m session-id)
          (assoc-in m [session-id :entries] [])
          m)))
    @result))

(defn match-summary
  "Finds a summary entry where t-start < timestamp < t-end.
   Returns the summary string or nil."
  [entries ^Instant t-start ^Instant t-end]
  (some (fn [{:keys [summary ^Instant timestamp]}]
          (when (and (.isAfter timestamp t-start)
                     (.isBefore timestamp t-end))
            summary))
        entries))

(defn reset-buffer!
  "Clears the entire buffer. For testing."
  []
  (reset! session-buffer {}))
