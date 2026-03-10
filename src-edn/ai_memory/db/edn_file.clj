;; @ai-generated(solo)
(ns ai-memory.db.edn-file
  "EDN file-backed database: atom for fast reads, agent for async writes.

   State shape:
     {:nodes   {eid {:db/id eid :node/content \"...\" ...}}
      :edges   {eid {:db/id eid :edge/id uuid ...}}
      :tags    {\"name\" {:tag/name \"name\" :tag/node-count 0 ...}}
      :tick    0
      :next-id 1}

   Persistence strategy:
   - All reads from atom (instant)
   - Mutations via swap!, then send-off to agent for async disk write
   - Crash safety: write to .tmp then atomic rename
   - External change detection: poll file mtime every 2s, reload if changed"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [ai-memory.schema :as schema])
  (:import [java.io File PushbackReader]
           [java.util Date]))

;; --- Initial state ---

(def ^:private empty-state
  {:nodes   {}
   :edges   {}
   :tags    {}
   :tick    0
   :next-id 1})

(defn- seed-tags
  "Adds aspect tags to initial state."
  [state]
  (update state :tags
          (fn [tags]
            (reduce (fn [m name]
                      (if (contains? m name)
                        m
                        (assoc m name {:tag/name name :tag/node-count 0 :tag/tier :aspect})))
                    tags
                    schema/aspect-tags))))

;; --- File I/O ---

(defn- read-edn-file
  "Reads EDN state from file. Returns nil if file doesn't exist or is empty."
  [^File file]
  (when (.exists file)
    (let [content (slurp file)]
      (when (seq content)
        (edn/read-string {:readers {'inst #(if (instance? Date %)
                                             %
                                             (Date. (.getTime (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS-00:00")
                                                              (.parse (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss.SSS-00:00") %))))}}
                         content)))))

(defn- write-edn-file!
  "Writes state to file atomically: .tmp then rename."
  [^File file state]
  (let [tmp (File. (str (.getPath file) ".tmp"))]
    (spit tmp (pr-str state))
    ;; atomic rename — prevents corruption on crash mid-write
    (.renameTo tmp file)))

(defn- file-mtime
  "Returns file's last-modified timestamp in ms, or 0 if missing."
  [^File file]
  (if (.exists file) (.lastModified file) 0))

;; --- Database record ---

(defrecord EdnDb [state-atom   ;; atom holding the full state map
                  file         ;; java.io.File for persistence
                  write-agent  ;; agent serializing disk writes
                  last-write-ms ;; atom<long> — mtime after our last write
                  watcher      ;; atom<Thread> — mtime polling daemon
                  ])

(defn- persist!
  "Schedules async write of current atom state to disk.
   Multiple rapid calls coalesce — agent always writes latest state."
  [{:keys [state-atom file write-agent last-write-ms]}]
  (send-off write-agent
            (fn [_]
              (try
                (write-edn-file! file @state-atom)
                (reset! last-write-ms (file-mtime file))
                (catch Exception e
                  (log/error e "Failed to persist EDN state to" (.getPath file)))))))

(defn- reload-from-disk!
  "Reloads state from file into atom. Called when external change detected."
  [{:keys [state-atom file last-write-ms]}]
  (try
    (when-let [disk-state (read-edn-file file)]
      (reset! state-atom disk-state)
      (reset! last-write-ms (file-mtime file))
      (log/info "EDN DB reloaded from disk (external change detected)"))
    (catch Exception e
      (log/error e "Failed to reload EDN state from" (.getPath file)))))

(defn- start-watcher!
  "Starts a daemon thread that polls file mtime every 2s.
   Reloads atom if file was modified externally (mtime > our last write)."
  [db]
  (let [running (atom true)
        thread  (Thread.
                 (fn []
                   (while @running
                     (try
                       (Thread/sleep 2000)
                       (let [mtime (file-mtime (:file db))]
                         (when (> mtime @(:last-write-ms db))
                           (reload-from-disk! db)))
                       (catch InterruptedException _
                         (reset! running false))
                       (catch Exception e
                         (log/error e "EDN watcher error"))))))]
    (.setDaemon thread true)
    (.setName thread "edn-db-watcher")
    (.start thread)
    (reset! (:watcher db) thread)
    running))

;; --- Public API ---

(defn mutate!
  "Applies f to current state via swap!, persists async.
   Returns the new state. Thread-safe (atom semantics)."
  [db f & args]
  (let [new-state (apply swap! (:state-atom db) f args)]
    (persist! db)
    new-state))

(defn state
  "Returns current in-memory state. Instant, no disk I/O."
  [db]
  @(:state-atom db))

(defn next-id!
  "Allocates and returns the next entity ID. Atomic."
  [db]
  (let [new-state (swap! (:state-atom db) update :next-id inc)]
    (dec (:next-id new-state))))

(defn current-tick
  "Returns the current global tick value."
  [db]
  (:tick (state db)))

(defn next-tick!
  "Increments tick and persists. Returns the new tick value."
  [db]
  (let [new-state (swap! (:state-atom db) update :tick inc)]
    (persist! db)
    (:tick new-state)))

;; --- Lifecycle ---

(defn connect
  "Opens (or creates) an EDN file database.
   `file-path` — path to the .edn file. nil for in-memory only (no persistence).
   Returns an EdnDb record."
  [file-path]
  (let [file          (when file-path (io/file file-path))
        initial-state (if file
                        (or (read-edn-file file) empty-state)
                        empty-state)
        state         (seed-tags initial-state)
        db            (->EdnDb (atom state)
                               file
                               (agent nil :error-mode :continue)
                               (atom (if file (file-mtime file) 0))
                               (atom nil))]
    ;; Persist seeded tags if file is new
    (when (and file (not= initial-state state))
      (persist! db))
    ;; Start file watcher for external change detection
    (when file
      (start-watcher! db))
    (log/info "EDN DB connected:" (or file-path "<in-memory>"))
    db))

(defn close
  "Stops the watcher thread and awaits final write."
  [db]
  (when-let [thread @(:watcher db)]
    (.interrupt thread))
  ;; Await pending writes (up to 5s)
  (await-for 5000 (:write-agent db))
  (log/info "EDN DB closed"))
