#!/usr/bin/env bb
;; SessionStart hook: auto-loads memory context into the session.
;;
;; Fires on: startup, clear, compact (skips resume).
;; Calls ai-memory HTTP API and outputs formatted context to stdout,
;; which becomes a system-reminder visible to the agent.
;;
;; Loads: preferences, universal facts, project facts, tags overview,
;; recent sessions, current session (on clear/compact), timestamp.

(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def base-url (or (System/getenv "AI_MEMORY_URL") "http://localhost:8080"))

;; --- HTTP helpers ---

(defn api-get [path & [params]]
  (try
    (let [resp (http/get (str base-url path)
                 (cond-> {:headers {"Accept" "application/json"}}
                   params (assoc :query-params params)))]
      (json/parse-string (:body resp) true))
    (catch Exception _ nil)))

(defn api-post [path body]
  (try
    (let [resp (http/post (str base-url path)
                 {:headers {"Content-Type" "application/json"
                            "Accept"       "application/json"}
                  :body    (json/generate-string body)})]
      (json/parse-string (:body resp) true))
    (catch Exception _ nil)))

;; --- Read hook input ---

(def input (json/parse-string (slurp *in*) true))
(def session-id (:session_id input))
(def hook-source (:source input))
(def cwd (:cwd input))

;; Skip on resume — context already in window
(when (= hook-source "resume")
  (System/exit 0))

;; --- Detect project from cwd ---

(def project-name
  (when cwd
    (let [parts (str/split cwd #"/")]
      (last parts))))

;; --- Session continuation (on clear) ---

(def continuation-result
  (when (and (= hook-source "clear") project-name session-id)
    (let [state-dir  (str (System/getenv "HOME") "/.claude/hooks/state")
          cache-file (str state-dir "/prev-session-" project-name ".edn")]
      (when (fs/exists? cache-file)
        (let [cache  (read-string (slurp cache-file))
              result (api-post "/api/session/continue"
                       {:prev_session_id (:session-id cache)
                        :session_id      session-id
                        :project         project-name})]
          (fs/delete cache-file)
          result)))))

;; --- Fetch data from API ---

(def tags-data (api-get "/api/tags" {"limit" "50"}))

(def fact-filters
  (cond-> [{:tags ["pref"]}
           {:tags ["universal"]}
           {:tags ["session"] :sort_by "date" :limit 5}]
    ;; Add project filter if project detected
    project-name (conj {:tags [project-name]})
    ;; Add current session on clear/compact
    (and session-id (#{"clear" "compact"} hook-source))
    (conj {:session_id session-id})))

(def facts-data (api-post "/api/tags/facts" {:filters fact-filters}))

;; --- Format output ---

(defn format-facts [results filter-pred label]
  (when-let [group (first (filter filter-pred results))]
    (let [facts (:facts group)]
      (when (seq facts)
        (str "## " label "\n"
             (str/join "\n"
               (map (fn [f]
                      (let [content  (get f (keyword "node/content"))
                            blob-dir (get f (keyword "node/blob-dir"))
                            tags     (->> (get f (keyword "node/tag-refs"))
                                          (map #(get % (keyword "tag/name"))))]
                        (str "- " content
                             (when blob-dir
                               (str " [blob: " blob-dir "]"))
                             (when (seq tags)
                               (str " {" (str/join ", " tags) "}")))))
                    facts)))))))

(defn format-tag [t]
  (str (get t (keyword "tag/name"))
       " (" (or (get t (keyword "tag/node-count")) 0) ")"))

(defn format-tags [tags]
  (when (seq tags)
    (let [{aspect true other false}
          (group-by #(= "aspect" (get % (keyword "tag/tier"))) tags)
          aspect  (sort-by (keyword "tag/name") aspect)
          other   (->> other
                       (remove #(#{"session" "universal"} (get % (keyword "tag/name"))))
                       (sort-by (keyword "tag/node-count") (fn [a b] (compare (or b 0) (or a 0)))))]
      (str "## Tags\n"
           (when (seq aspect)
             (str "Aspect: " (str/join ", " (map format-tag aspect)) "\n"))
           (when (seq other)
             (str/join ", " (map format-tag other)))))))

(defn format-sessions [facts]
  (when (seq facts)
    (str "## Recent Sessions\n"
         (str/join "\n"
           (map (fn [f]
                  (let [content    (get f (keyword "node/content"))
                        blob-dir   (get f (keyword "node/blob-dir"))
                        updated    (get f (keyword "node/updated-at"))
                        date-str   (when updated
                                     (subs (str updated) 0 (min 10 (count (str updated)))))]
                    (str "- " (or date-str "?") ": "
                         (or content "(no summary)")
                         (when blob-dir (str " [blob: " blob-dir "]")))))
                facts)))))

(defn format-timestamp []
  (let [now    (java.time.ZonedDateTime/now)
        fmt    (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm (EEE)")]
    (.format now fmt)))

;; --- Assemble output ---

(let [results (:results facts-data)

      pref-section
      (format-facts results
        (fn [r] (= (get-in r [:filter :tags]) ["pref"]))
        "Preferences")

      universal-section
      (format-facts results
        (fn [r] (= (get-in r [:filter :tags]) ["universal"]))
        "Universal")

      project-section
      (when project-name
        (format-facts results
          (fn [r] (= (get-in r [:filter :tags]) [project-name]))
          (str "Project: " project-name)))

      session-section
      (when (#{"clear" "compact"} hook-source)
        (format-facts results
          (fn [r] (some? (get-in r [:filter :session-id])))
          "Current Session (continued)"))

      sessions-section
      (let [session-group (first (filter
                                   (fn [r] (= (get-in r [:filter :tags]) ["session"]))
                                   results))]
        (format-sessions (:facts session-group)))

      tags-section    (format-tags tags-data)
      timestamp       (format-timestamp)

      blob-location
      (let [home     (System/getenv "HOME")
            settings (try (json/parse-string (slurp (str home "/.claude/settings.json")) true)
                          (catch Exception _ nil))
            mcp-env  (get-in settings [:mcpServers :ai-memory :env])
            mount    (or (:AI_MEMORY_BLOB_MOUNT mcp-env)
                        (System/getenv "AI_MEMORY_BLOB_MOUNT"))]
        (str "## Blobs\nAll blob directories are under `"
             (or mount "~/.ai-memory/blobs") "/`"))

      continuation-section
      (when-let [prev-dir (:prev-blob-dir continuation-result)]
        (str "## Continuation\nThis session continues a previous conversation. Previous blob: " prev-dir))

      sections (remove nil? [pref-section
                             universal-section
                             project-section
                             continuation-section
                             session-section
                             sessions-section
                             blob-location
                             tags-section])]

  (when (seq sections)
    (println (str "# Memory Context\n\n"
                  (str/join "\n\n" sections)
                  "\n\n---\n" timestamp))))

(System/exit 0)
