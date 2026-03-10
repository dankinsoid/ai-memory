#!/usr/bin/env bb
;; SessionStart hook: auto-loads memory context into the session.
;;
;; Fires on: startup (skips resume).
;; Calls ai-memory HTTP API and outputs formatted context to stdout,
;; which becomes a system-reminder visible to the agent.
;;
;; Loads facts in priority order per scope (universal, project):
;;   1. critical-rule  2. rule  3. preference  4. remaining
;; Cascading exclude_tags ensure server-side disjointness.
;; Global dedup by :db/id across scopes.
;;
;; Env-var toggles (set any to disable):
;;   AI_MEMORY_DISABLED=1     — master switch (all hooks)
;;   AI_MEMORY_NO_READ=1      — don't inject any context
;;   AI_MEMORY_NO_SESSIONS=1  — skip recent sessions section
;;   AI_MEMORY_NO_FACTS=1     — skip facts sections

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

(def no-sessions? (System/getenv "AI_MEMORY_NO_SESSIONS"))
(def no-facts? (System/getenv "AI_MEMORY_NO_FACTS"))

(defn- auth-headers []
  (cond-> {"Accept" "application/json"}
    api-token (assoc "Authorization" (str "Bearer " api-token))))

;; --- HTTP helpers ---
;; Track API errors so we can warn the user if memory server is unreachable.

(def api-errors (atom []))

(defn api-get [path & [params]]
  (try
    (let [resp (http/get (str base-url path)
                 (cond-> {:headers (auth-headers)}
                   params (assoc :query-params params)))]
      (json/parse-string (:body resp) true))
    (catch Exception e
      (swap! api-errors conj {:path path :error (.getMessage e)})
      nil)))

(defn api-post [path body]
  (try
    (let [resp (http/post (str base-url path)
                 {:headers (merge (auth-headers)
                                  {"Content-Type" "application/json"})
                  :body    (json/generate-string body)})]
      (json/parse-string (:body resp) true))
    (catch Exception e
      (swap! api-errors conj {:path path :error (.getMessage e)})
      nil)))

;; --- Read hook input ---

(def input (json/parse-string (slurp *in*) true))
(def cwd (:cwd input))

(when (= (:source input) "resume")
  (System/exit 0))

(when (or (System/getenv "AI_MEMORY_DISABLED") (System/getenv "AI_MEMORY_NO_READ"))
  (System/exit 0))

;; --- Detect project from cwd ---

(def project-name (derive-project cwd))

;; --- Priority tiers & budgets ---

(def priority-tiers ["critical-rule" "rule" "preference" "conventions"])
(def tier-limit 10)
(def universal-budget 5)
(def project-budget 10)

;; --- Filter generation ---
;; Each tier excludes all higher-priority tiers (cascading),
;; so results are disjoint without client-side dedup within a scope.

(defn scope-filters
  "Generate priority-tiered filters for a scope.
   Returns filters for: critical-rule, rule, preference, conventions, catch-all."
  [scope-tag base-excludes budget]
  (let [tier-filters
        (loop [tiers priority-tiers, prev-tiers [], acc []]
          (if (empty? tiers)
            acc
            (let [tier     (first tiers)
                  excludes (into base-excludes prev-tiers)]
              (recur (rest tiers)
                     (conj prev-tiers tier)
                     (conj acc (cond-> {:tags [tier scope-tag] :limit tier-limit}
                                 (seq excludes) (assoc :exclude_tags (vec excludes))))))))
        ;; Catch-all: exclude all tiers + base, sort by weight so proven facts come first
        all-excludes (into base-excludes priority-tiers)]
    (conj tier-filters
          (cond-> {:tags [scope-tag] :limit budget :sort_by "weight"}
            (seq all-excludes) (assoc :exclude_tags (vec all-excludes))))))

;; --- Build all filters ---

(def tags-data (api-get "/api/tags" {"limit" "50"}))

(def fact-filters
  (if project-name
    (let [ptag (str "project/" project-name)]
      (vec (concat
        ;; Universal scope: no base excludes
        (scope-filters "universal" [] universal-budget)
        ;; Project summary
        [{:tags ["project" ptag]}]
        ;; Project scope: exclude session + universal; sort catch-all by weight
        (scope-filters ptag ["session" "universal"] project-budget)
        ;; Sessions
        [{:tags ["session" ptag] :sort_by "date" :limit 4}])))
    (vec (concat
      (scope-filters "universal" [] universal-budget)
      [{:tags ["session"] :sort_by "date" :limit 4}]))))

(def facts-data (api-post "/api/tags/facts" {:filters fact-filters}))

;; --- Helpers ---

(defn find-result
  "Find a result group matching a filter spec (tags + exclude_tags).
   API returns :exclude-tags (dashed) while we send :exclude_tags (underscored)."
  [results filter-spec]
  (first (filter #(and (= (get-in % [:filter :tags]) (:tags filter-spec))
                       (= (get-in % [:filter :exclude-tags]) (:exclude_tags filter-spec)))
                 results)))

(defn build-scope
  "Collect facts for a scope in priority order. Dedup by seen-ids, respect budget.
   Returns {:tier-groups [[tier facts]...], :seen-ids #{...}}."
  [results scope-tag base-excludes budget seen-ids]
  (let [filters    (scope-filters scope-tag base-excludes budget)
        tier-names (conj (vec priority-tiers) nil)
        pairs      (map vector tier-names (map #(find-result results %) filters))]
    (loop [ps pairs, seen seen-ids, acc [], left budget]
      (if (or (empty? ps) (<= left 0))
        {:tier-groups acc :seen-ids seen}
        (let [[tier result] (first ps)
              new (->> (or (:facts result) [])
                       (remove #(contains? seen (:db/id %)))
                       (take left)
                       vec)]
          (recur (rest ps)
                 (into seen (map :db/id new))
                 (if (seq new) (conj acc [tier new]) acc)
                 (- left (count new))))))))

;; --- Format output ---

(defn- format-fact [f]
  (let [content  (get f (keyword "node/content"))
        blob-dir (get f (keyword "node/blob-dir"))
        tags     (get f (keyword "node/tags"))]
    (str "- " content
         (when blob-dir (str " [blob: " blob-dir "]"))
         (when (seq tags) (str " {" (str/join ", " tags) "}")))))

(defn- format-fact-with-tier [tier f]
  (let [prefix  (case tier
                  "critical-rule" "[!] "
                  "rule"          "[rule] "
                  "preference"    "[pref] "
                  "")
        content  (get f (keyword "node/content"))
        blob-dir (get f (keyword "node/blob-dir"))]
    (str "- " prefix content
         (when blob-dir (str " [blob: " blob-dir "]")))))

(defn- format-tier-groups
  "Format tier-groups into lines. Returns nil if empty."
  [tier-groups]
  (let [lines (mapcat (fn [[tier facts]]
                        (map (partial format-fact-with-tier tier) facts))
                      tier-groups)]
    (when (seq lines) (str/join "\n" lines))))

(defn- split-tier-groups
  "Split tier-groups into {:rules [[tier facts]...], :facts [[nil facts]...]}."
  [tier-groups]
  (let [{rules true facts false}
        (group-by (fn [[tier _]] (some? tier)) tier-groups)]
    {:rules (vec (or rules []))
     :facts (vec (or facts []))}))

(defn format-session-line [f]
  (let [content  (get f (keyword "node/content"))
        blob-dir (get f (keyword "node/blob-dir"))
        lines    (some-> content (str/split #"\n"))
        title    (first lines)
        summary  (second lines)]
    (str "- " (or title "(no summary)")
         (when summary (str " — " summary))
         (when blob-dir (str " [blob: " blob-dir "]")))))

(defn format-sessions [facts label]
  (when (seq facts)
    (str "## " label "\n"
         (str/join "\n" (map format-session-line facts)))))

(defn format-tag [t]
  (str (get t (keyword "tag/name"))
       " (" (or (get t (keyword "tag/node-count")) 0) ")"))

;; Aspect tags determined by :tag/tier from server, not hardcoded
(defn format-tags [tags]
  (let [aspect (->> tags
                    (filter #(= "aspect" (get % (keyword "tag/tier"))))
                    (sort-by (keyword "tag/name")))
        other  (->> tags
                    (remove #(= "aspect" (get % (keyword "tag/tier"))))
                    (remove #(#{"session" "universal"} (get % (keyword "tag/name"))))
                    (sort-by (keyword "tag/node-count") (fn [a b] (compare (or b 0) (or a 0)))))]
    (str "## Top Tags\n"
         "Fixed: " (str/join ", " (map format-tag aspect)) "\n"
         (when (seq other)
           (str "Dynamic: " (str/join ", " (map format-tag other)))))))

(defn format-timestamp []
  (let [now (java.time.ZonedDateTime/now)
        fmt (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm (EEE)")]
    (.format now fmt)))

;; --- Assemble output ---

(let [results (:results facts-data)
      seen    (atom #{})

      ;; Universal scope
      universal-section
      (when-not no-facts?
        (let [{:keys [tier-groups seen-ids]}
              (build-scope results "universal" [] universal-budget @seen)
              {:keys [rules facts]} (split-tier-groups tier-groups)
              rules-lines (when (seq rules)
                            (str "### Rules\n" (format-tier-groups rules)))
              facts-lines (when (seq facts)
                            (str "### Facts\n" (format-tier-groups facts)))
              body (str/join "\n" (remove nil? [rules-lines facts-lines]))]
          (reset! seen seen-ids)
          (when (seq body)
            (str "## Universal\n" body))))

      ;; Project scope
      project-section
      (when (and project-name (not no-facts?))
        (let [ptag (str "project/" project-name)
              ;; Project summary (separate, not budgeted)
              summary-spec  {:tags ["project" ptag]}
              summary-facts (or (:facts (find-result results summary-spec)) [])
              _ (swap! seen into (map :db/id summary-facts))
              ;; Project sessions
              session-spec  {:tags ["session" ptag]}
              session-facts (or (:facts (find-result results session-spec)) [])
              _ (swap! seen into (map :db/id session-facts))
              ;; Prioritized project facts
              {:keys [tier-groups seen-ids]}
              (build-scope results ptag ["session" "universal"] project-budget @seen)]
          (reset! seen seen-ids)
          (let [{:keys [rules facts]} (split-tier-groups tier-groups)
                session-lines (when (seq session-facts)
                                (str "### Sessions\n"
                                     (str/join "\n" (map format-session-line session-facts))))
                summary-lines (when (seq summary-facts)
                                (str/join "\n" (map format-fact summary-facts)))
                rules-lines   (when (seq rules)
                                (str "### Rules\n" (format-tier-groups rules)))
                facts-lines   (when (seq facts)
                                (str "### Facts\n" (format-tier-groups facts)))
                parts (remove nil?
                        [session-lines
                         (when (or summary-lines rules-lines)
                           (str/join "\n" (remove nil? [summary-lines rules-lines])))
                         facts-lines])]
            (when (seq parts)
              (str "## Project: " project-name "\n"
                   (str/join "\n" parts))))))

      ;; Other project sessions — disabled for now, revisit later
      sessions-section nil
      #_(when-not no-sessions?
        (let [cross-spec {:tags ["session"] :exclude_tags [(str "project/" project-name)]}
              no-proj-spec {:tags ["session"]}
              facts (or (:facts (find-result results cross-spec))
                        (:facts (find-result results no-proj-spec))
                        [])
              deduped (remove #(contains? @seen (:db/id %)) facts)]
          (swap! seen into (map :db/id deduped))
          (format-sessions deduped "Other Projects")))

      ;; Aspect tags only (lightweight orientation)
      aspects-section
      (let [aspect-names (->> tags-data
                              (filter #(= "aspect" (get % (keyword "tag/tier"))))
                              (map #(get % (keyword "tag/name")))
                              sort)]
        (when (seq aspect-names)
          (str "Aspect tags: " (str/join ", " aspect-names))))

      timestamp    (format-timestamp)

      sections (remove nil? [universal-section
                             project-section
                             sessions-section
                             aspects-section])]

  (if (seq sections)
    (println (str "# Memory Context\n\n"
                  (str/join "\n\n" sections)
                  "\n\n---\n" timestamp))
    ;; No sections at all — API likely down
    (when (seq @api-errors)
      (println (str "# ⚠ Memory Unavailable\n\n"
                    "ai-memory server is unreachable (" base-url "). "
                    "Memory context was NOT loaded. "
                    "MCP tools (memory_remember, memory_session, etc.) will likely fail too.\n"
                    "Tell the user that their memory plugin is down.")))))

(System/exit 0)
