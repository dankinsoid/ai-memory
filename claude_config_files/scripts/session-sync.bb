#!/usr/bin/env bb
;; Stop hook: parses Claude Code session JSONL, sends new messages to ai-memory server.
;; Runs after every agent response. Sends only delta (messages since last sync).

(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[clojure.java.io :as io])

(defn find-transcript
  "Finds the JSONL transcript file for a session.
   Claude Code stores transcripts at ~/.claude/projects/<hash>/<session-id>.jsonl"
  [session-id]
  (let [projects-dir (str (System/getenv "HOME") "/.claude/projects")]
    (when (fs/exists? projects-dir)
      (->> (fs/glob projects-dir (str "**/" session-id ".jsonl"))
           (map str)
           first))))

(defn parse-jsonl
  "Reads JSONL file, returns seq of parsed entries."
  [path]
  (with-open [rdr (io/reader path)]
    (->> (line-seq rdr)
         (keep (fn [line]
                 (try (json/parse-string line true)
                      (catch Exception _ nil))))
         vec)))

(defn extract-messages
  "Filters to user/assistant message entries."
  [entries]
  (->> entries
       (filter #(#{"user" "assistant"} (:type %)))
       (mapv (fn [e]
               {:uuid      (:uuid e)
                :timestamp (:timestamp e)
                :type      (:type e)
                :role      (get-in e [:message :role])
                :content   (get-in e [:message :content])}))))

(defn delta-messages
  "Returns messages after last-uuid. If last-uuid not found, returns all."
  [entries last-uuid]
  (if last-uuid
    (let [after (->> entries
                     (drop-while #(not= (:uuid %) last-uuid))
                     rest)]
      (if (seq after)
        (extract-messages after)
        ;; last-uuid not found (e.g. /clear) — send all
        (extract-messages entries)))
    (extract-messages entries)))

(let [input      (json/parse-string (slurp *in*) true)
      session-id (:session_id input)
      cwd        (:cwd input)
      state-dir  (str (System/getenv "HOME") "/.claude/hooks/state")
      state-file (str state-dir "/" session-id ".edn")
      port       (or (System/getenv "AI_MEMORY_PORT") "8080")]

  (when session-id
    (when-let [transcript (or (:transcript_path input)
                              (find-transcript session-id))]
      (when (fs/exists? transcript)
        (let [last-uuid (when (fs/exists? state-file)
                          (:last-uuid (read-string (slurp state-file))))
              entries   (parse-jsonl transcript)
              messages  (delta-messages entries last-uuid)]

          (when (seq messages)
            (try
              (http/post (str "http://localhost:" port "/api/session/sync")
                         {:headers {"Content-Type" "application/json"}
                          :body    (json/generate-string
                                     {:session_id session-id
                                      :cwd        cwd
                                      :messages   messages})})
              (catch Exception e
                (binding [*out* *err*]
                  (println "session-sync: POST failed:" (.getMessage e)))))

            ;; Save sync state
            (fs/create-dirs state-dir)
            (spit state-file (pr-str {:last-uuid (:uuid (last messages))}))))))))
