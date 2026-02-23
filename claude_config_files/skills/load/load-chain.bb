#!/usr/bin/env bb
;; Load skill script: discovers session chain and outputs combined context.
;;
;; Usage:
;;   bb load-chain.bb <session-id>          # traverse continuation chain
;;   bb load-chain.bb --blob <blob-dir>     # load specific session blob
;;
;; Chain mode: calls /api/session/chain, strengthens first edge,
;; fetches compact.md from each session blob via /api/blobs/exec.
;; Blob mode: directly reads compact.md from a specific blob dir.

(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
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
          result     (api-post "/api/session/chain"
                       {:session_id session-id
                        :strengthen true})
          chain      (:chain result)]
      (if (empty? chain)
        (println "No continuation chain found for session" session-id)
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
          (println "Continuation edge strengthened."))))

    :else
    (do (println "Usage: bb load-chain.bb <session-id>")
        (println "       bb load-chain.bb --blob <blob-dir>")
        (System/exit 1))))

(System/exit 0)
