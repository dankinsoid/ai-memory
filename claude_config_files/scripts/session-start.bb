#!/usr/bin/env bb
;; SessionStart hook: auto-loads memory context into the session.
;;
;; Fires on: startup (skips resume).
;; Calls ai-memory HTTP API and outputs formatted context to stdout,
;; which becomes a system-reminder visible to the agent.
;;
;; Loads: preferences, universal facts, project facts, tags overview,
;; recent sessions, timestamp. Lightweight — /load for deep recovery.

(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
         '[babashka.process :as process]
         '[clojure.string :as str])

(defn git-project-name [cwd]
  (when cwd
    (try
      (let [result (process/sh "git" "-C" cwd "remote" "get-url" "origin")]
        (when (zero? (:exit result))
          (-> (str/trim (:out result))
              (str/replace #"\.git$" "")
              (str/split #"[/:]")
              last)))
      (catch Exception _ nil))))

(defn derive-project [cwd]
  (or (git-project-name cwd)
      (when cwd (last (str/split cwd #"/")))))

(def base-url (or (System/getenv "AI_MEMORY_URL") "http://localhost:8080"))
(def api-token (System/getenv "AI_MEMORY_TOKEN"))

(defn- auth-headers []
  (cond-> {"Accept" "application/json"}
    api-token (assoc "Authorization" (str "Bearer " api-token))))

;; --- HTTP helpers ---

(defn api-get [path & [params]]
  (try
    (let [resp (http/get (str base-url path)
                 (cond-> {:headers (auth-headers)}
                   params (assoc :query-params params)))]
      (json/parse-string (:body resp) true))
    (catch Exception _ nil)))

(defn api-post [path body]
  (try
    (let [resp (http/post (str base-url path)
                 {:headers (merge (auth-headers)
                                  {"Content-Type" "application/json"})
                  :body    (json/generate-string body)})]
      (json/parse-string (:body resp) true))
    (catch Exception _ nil)))

;; --- Read hook input ---

(def input (json/parse-string (slurp *in*) true))
(def cwd (:cwd input))

;; Skip on resume — context already in window
(when (= (:source input) "resume")
  (System/exit 0))

;; --- Detect project from cwd ---

(def project-name (derive-project cwd))

;; --- Fetch data from API ---

(def tags-data (api-get "/api/tags" {"limit" "50"}))

(def fact-filters
  (cond-> [{:tags ["interest"] :sort_by "date" :limit 5}
           {:tags ["pref"]}
           {:tags ["universal"]}
           {:tags ["session"] :sort_by "date" :limit 5}]
    project-name (conj {:tags [project-name]})))

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
                               (str " {" (str/join ", " tags) "}"))
                             (when-let [ew (get f (keyword "node/effective-weight"))]
                               (str " w:" (format "%.2f" (double ew)))))))
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

      interests-section
      (format-facts results
        (fn [r] (= (get-in r [:filter :tags]) ["interest"]))
        "Current Interests")

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

      sessions-section
      (let [session-group (first (filter
                                   (fn [r] (= (get-in r [:filter :tags]) ["session"]))
                                   results))]
        (format-sessions (:facts session-group)))

      tags-section    (format-tags tags-data)
      timestamp       (format-timestamp)

      blob-section
      "## Blobs\nUse `memory_read_blob` to explore blob contents."

      sections (remove nil? [interests-section
                             pref-section
                             universal-section
                             project-section
                             sessions-section
                             blob-section
                             tags-section])]

  (when (seq sections)
    (println (str "# Memory Context\n\n"
                  (str/join "\n\n" sections)
                  "\n\n---\n" timestamp))))

(System/exit 0)
