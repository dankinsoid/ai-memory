#!/usr/bin/env bb
;; Load skill script: discovers session chain and outputs combined context.
;;
;; Usage:
;;   bb load-chain.bb <session-id>          # traverse continuation chain
;;   bb load-chain.bb --blob <blob-dir>     # load specific session blob
;;
;; Chain mode: checks for prev-session cache files (written by SessionEnd
;; hook on /clear), creates continuation edges via /api/session/continue,
;; then calls /api/session/chain to traverse the full chain.
;; Blob mode: directly reads compact.md from a specific blob dir.

(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def base-url (or (System/getenv "AI_MEMORY_URL") "http://localhost:8080"))

(defn api-post [path body]
  (try
    (let [resp (http/post (str base-url path)
                {:headers {"Content-Type" "application/json"
                           "Accept"       "application/json"}
                 :body    (json/generate-string body)})]
      (json/parse-string (:body resp) true))
    (catch Exception e
      (binding [*out* *err*]
        (println "API error:" (.getMessage e)))
      nil)))

(defn read-blob-file
  "Reads a file from a blob dir via /api/blobs/exec. Returns content or nil."
  [blob-dir filename]
  (let [result (api-post "/api/blobs/exec"
                 {:blob_dir blob-dir
                  :command  (str "cat " filename " 2>/dev/null")})]
    (when (and result (zero? (or (:exit-code result) -1)))
      (let [stdout (:stdout result)]
        (when-not (str/blank? stdout)
          stdout)))))

(defn truncate-lines
  "Truncates text to max-lines, appending a note if truncated."
  [text max-lines]
  (let [lines (str/split-lines text)]
    (if (<= (count lines) max-lines)
      text
      (str (str/join "\n" (take max-lines lines))
           "\n\n... (" (- (count lines) max-lines) " more lines)"))))

(defn read-blob-tail
  "Reads last N lines of the latest transcript .md file in a blob."
  [blob-dir n]
  (let [result (api-post "/api/blobs/exec"
                 {:blob_dir blob-dir
                  :command  "ls -t *.md 2>/dev/null | grep -v compact.md | head -1"})]
    (when (and result (zero? (or (:exit-code result) -1)))
      (let [filename (str/trim (:stdout result))]
        (when-not (str/blank? filename)
          (let [tail-result (api-post "/api/blobs/exec"
                              {:blob_dir blob-dir
                               :command  (str "tail -" n " " filename)})]
            (when (and tail-result (zero? (or (:exit-code tail-result) -1)))
              (let [content (:stdout tail-result)]
                (when-not (str/blank? content)
                  {:filename filename :content content})))))))))

(defn print-blob-content
  "Prints compact.md from a blob, falling back to _current.md.
   When compact.md exists, also prints last conversation turns."
  [blob-dir]
  (if-let [compact (read-blob-file blob-dir "compact.md")]
    (do (println compact)
        (when-let [{:keys [filename content]} (read-blob-tail blob-dir 30)]
          (println)
          (println (str "## Last Conversation Turns (from " filename ")"))
          (println)
          (println content)))
    (if-let [current (read-blob-file blob-dir "_current.md")]
      (do (println "*No compact.md — showing _current.md (raw transcript):*")
          (println)
          (println (truncate-lines current 80)))
      (println "*No compact.md or _current.md found.*"))))

;; --- Main ---

(let [args  *command-line-args*
      flag  (first args)
      value (second args)]
  (cond
    ;; Direct blob loading
    (= flag "--blob")
    (if-not value
      (do (println "Usage: bb load-chain.bb --blob <blob-dir>")
          (System/exit 1))
      (do
        (println "# Session Recovery")
        (println)
        (print-blob-content value)))

    ;; Chain traversal (default)
    flag
    (let [session-id flag
          ;; Check for prev-session cache files from Stop/SessionEnd hooks.
          ;; If found, create continuation edge before traversing chain.
          ;; Skip cache files that reference the current session.
          state-dir  (str (System/getenv "HOME") "/.claude/hooks/state")
          _          (doseq [f (fs/glob state-dir "prev-session-*.edn")]
                       (try
                         (let [cache   (read-string (slurp (str f)))
                               project (:project cache)
                               prev-id (:session-id cache)]
                           (when (and prev-id project
                                      (not= prev-id session-id))
                             (api-post "/api/session/continue"
                               {:prev_session_id prev-id
                                :session_id      session-id
                                :project         project})
                             (fs/delete f)))
                         (catch Exception _ nil)))
          result     (api-post "/api/session/chain"
                       {:session_id session-id
                        :strengthen true})
          chain      (:chain result)]
      (if (seq chain)
        (do
          (println "# Session Chain Recovery")
          (println)
          (println (str (count chain) " previous session(s) in chain."))
          (doseq [{:keys [blob-dir content]} chain
                  :when blob-dir]
            (println)
            (println "---")
            (println (str "## " (or content "(no summary)")))
            (println)
            (print-blob-content blob-dir))
          (println)
          (println "---")
          (println "Continuation edge strengthened."))
        ;; Fallback: no chain found — load most recent session blob from memory
        (let [resp     (api-post "/api/tags/facts"
                         {:filters [{:tags ["session"] :sort_by "date" :limit 5}]})
              facts    (:facts (first (:results resp)))
              ;; Only facts with blob-dir, skip current session
              ;; blob-dir uses short UUID prefix (first 8 chars)
              sid-prefix (subs session-id 0 (min 8 (count session-id)))
              prev     (->> facts
                            (filter #(get % (keyword "node/blob-dir")))
                            (remove #(str/includes?
                                       (get % (keyword "node/blob-dir"))
                                       sid-prefix))
                            first)]
          (if-let [blob-dir (get prev (keyword "node/blob-dir"))]
            (do
              (println "# Session Recovery")
              (println)
              (println (str "*Loaded from: " blob-dir "*"))
              (println)
              (print-blob-content blob-dir))
            (println "No previous session found.")))))

    :else
    (do (println "Usage: bb load-chain.bb <session-id>")
        (println "       bb load-chain.bb --blob <blob-dir>")
        (System/exit 1))))

(System/exit 0)
