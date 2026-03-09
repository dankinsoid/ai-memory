#!/usr/bin/env bb
;; Load skill script: discovers session chain and outputs combined context.
;;
;; Usage:
;;   bb load-chain.bb <session-id> [project]  # traverse continuation chain
;;   bb load-chain.bb --blob <blob-dir>        # load specific session blob
;;
;; Chain mode: checks for prev-session cache files (written by SessionEnd
;; hook on /clear), creates continuation edges via /api/session/continue,
;; then calls /api/session/chain to traverse the full chain.
;; When [project] is provided, only processes cache files for that project
;; and filters the fallback session query by project tag.
;; Blob mode: directly reads compact.md from a specific blob dir.
;;
;; Content strategy: shows last N chars of conversation (concatenated chunks).
;; If entire conversation fits in N chars, compact.md is skipped.
;; If conversation exceeds N chars, compact.md is shown first, then the tail.

(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def base-url (or (System/getenv "AI_MEMORY_URL") "http://localhost:8080"))
(def api-token (System/getenv "AI_MEMORY_TOKEN"))
(def context-chars 4000) ;; ~1000 words — conversation tail for recovery

(defn api-post [path body]
  (try
    (let [resp (http/post (str base-url path)
                {:headers (cond-> {"Content-Type" "application/json"
                                   "Accept"       "application/json"}
                            api-token (assoc "Authorization" (str "Bearer " api-token)))
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

(defn read-conversation
  "Reads conversation from blob — all numbered chunks + _current.md in order.
   Returns {:content str :total-size int :full? bool} or nil."
  [blob-dir max-chars]
  ;; Step 1: list conversation files and get total size
  (let [result (api-post "/api/blobs/exec"
                 {:blob_dir blob-dir
                  :command  (str "files=$(ls -1 *.md 2>/dev/null | grep -v compact.md | sort);"
                                 "if [ -z \"$files\" ]; then exit 1; fi;"
                                 "total=$(echo \"$files\" | xargs cat | wc -c);"
                                 "echo \"$files\";"
                                 "echo '---SIZE---';"
                                 "echo $total")})]
    (when (and result (zero? (or (:exit-code result) -1)))
      (let [output     (:stdout result)
            [file-sec size-sec] (str/split output #"---SIZE---\n?")
            files      (->> (str/split-lines (str/trim file-sec))
                            (remove str/blank?))
            total-size (some-> size-sec str/trim parse-long)]
        (when (and (seq files) total-size (pos? total-size))
          (let [all-files (str/join " " files)
                full?     (<= total-size max-chars)
                cmd       (if full?
                            (str "cat " all-files)
                            (str "cat " all-files " | tail -c " max-chars))
                content-result (api-post "/api/blobs/exec"
                                 {:blob_dir blob-dir :command cmd})]
            (when (and content-result (zero? (or (:exit-code content-result) -1)))
              {:content    (:stdout content-result)
               :total-size total-size
               :full?      full?})))))))

(defn trim-to-line-boundary
  "Trims content to start at the first newline (avoids partial first line)."
  [content]
  (if-let [idx (str/index-of content "\n")]
    (subs content (inc idx))
    content))

(defn read-blob-meta
  "Reads and parses meta.edn from a blob dir. Returns map or nil."
  [blob-dir]
  (when-let [content (read-blob-file blob-dir "meta.edn")]
    (try (read-string content) (catch Exception _ nil))))

(defn format-git-info
  "Formats :git map as a compact string, or nil if no git info."
  [git]
  (when git
    (let [{:keys [branch start-commit end-commit]} git
          commits (cond
                    (and start-commit end-commit (not= start-commit end-commit))
                    (str start-commit ".." end-commit)
                    (or end-commit start-commit)
                    (or end-commit start-commit))]
      (when (or branch commits)
        (str "*git: " (str/join " @ " (filter some? [branch commits])) "*")))))

(defn print-blob-content
  "Prints session content from a blob.
   Small conversations: full transcript, no compact.md needed.
   Large conversations: compact.md summary + last N chars of transcript."
  [blob-dir]
  (when-let [git-line (some-> (read-blob-meta blob-dir) :git format-git-info)]
    (println git-line)
    (println))
  (if-let [{:keys [content total-size full?]} (read-conversation blob-dir context-chars)]
    (if full?
      ;; Entire conversation fits — show it directly, skip compact
      (println content)
      ;; Large conversation — compact summary + tail
      (do
        (when-let [compact (read-blob-file blob-dir "compact.md")]
          (println compact)
          (println)
          (println "---")
          (println))
        (println "## Conversation Tail")
        (println)
        (println (str "*(last ~" (quot context-chars 1000) "K of "
                      (quot total-size 1000) "K total)*"))
        (println)
        (println (trim-to-line-boundary content))))
    ;; No conversation files — try compact.md alone
    (if-let [compact (read-blob-file blob-dir "compact.md")]
      (println compact)
      (println "*No content found.*"))))

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
          project    value  ;; optional 2nd arg: project name for isolation
          ;; Check for prev-session cache files from Stop/SessionEnd hooks.
          ;; If found, create continuation edge before traversing chain.
          ;; Skip cache files that reference the current session.
          ;; When project is provided, only process that project's cache file.
          state-dir  (str (System/getenv "HOME") "/.claude/hooks/state")
          cache-files (if project
                        (let [f (fs/path state-dir (str "prev-session-" project ".edn"))]
                          (when (fs/exists? f) [f]))
                        (fs/glob state-dir "prev-session-*.edn"))
          _          (doseq [f cache-files]
                       (try
                         (let [cache   (read-string (slurp (str f)))
                               proj    (:project cache)
                               prev-id (:session-id cache)]
                           (when (and prev-id proj
                                      (not= prev-id session-id))
                             (api-post "/api/session/continue"
                               {:prev_session_id prev-id
                                :session_id      session-id
                                :project         proj})
                             (fs/delete f)))
                         (catch Exception _ nil)))
          result     (api-post "/api/session/chain"
                       {:session_id session-id
                        :strengthen true})
          chain      (:chain result)]
      (if (seq chain)
        (let [latest (first chain)
              older  (rest chain)]
          (println "# Session Chain Recovery")
          (println)
          (println (str (count chain) " previous session(s) in chain."))
          ;; Older sessions: just show summary line from fact content
          (doseq [{:keys [content]} older]
            (println)
            (println "---")
            (println (str "## " (or content "(no summary)"))))
          ;; Most recent session: load full blob content
          (when-let [blob-dir (:blob-dir latest)]
            (println)
            (println "---")
            (println (str "## " (or (:content latest) "(no summary)")))
            (println)
            (print-blob-content blob-dir))
          (println)
          (println "---")
          (println "Continuation edge strengthened."))
        ;; Fallback: no chain found — load most recent session blob from memory
        ;; Filter by project tag if provided, to avoid cross-project contamination.
        (let [resp     (api-post "/api/tags/facts"
                         {:filters [{:tags    (cond-> ["session"] project (conj project))
                                     :sort_by "date" :limit 5}]})
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
    (do (println "Usage: bb load-chain.bb <session-id> [project]")
        (println "       bb load-chain.bb --blob <blob-dir>")
        (System/exit 1))))

(System/exit 0)
