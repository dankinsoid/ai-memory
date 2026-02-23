#!/usr/bin/env bb
;; Stop hook: syncs conversation transcript delta to server.
;;
;; Reads JSONL transcript, computes delta since last sync,
;; POSTs to /api/session/sync, saves state (last-uuid + chunk-bytes).
;; Also writes prev-session cache for continuation linking on /clear.
;; (SessionEnd hook is unreliable — see github.com/anthropics/claude-code/issues/6428)
;;
;; State file: ~/.claude/hooks/state/{session-id}.edn
;;   {:last-uuid "..." :chunk-bytes 12345}

(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn find-transcript
  "Finds the JSONL transcript file for a session."
  [session-id]
  (let [projects-dir (str (System/getenv "HOME") "/.claude/projects")]
    (when (fs/exists? projects-dir)
      (->> (fs/glob projects-dir (str "**/" session-id ".jsonl"))
           (map str)
           first))))

(defn parse-jsonl [path]
  (with-open [rdr (io/reader path)]
    (->> (line-seq rdr)
         (keep (fn [line]
                 (try (json/parse-string line true)
                      (catch Exception _ nil))))
         vec)))

(defn extract-messages [entries]
  (->> entries
       (filter #(and (#{"user" "assistant"} (:type %))
                     (not (:isMeta %))))
       (mapv (fn [e]
               {:uuid      (:uuid e)
                :timestamp (:timestamp e)
                :type      (:type e)
                :role      (get-in e [:message :role])
                :content   (get-in e [:message :content])}))))

(defn delta-messages [entries last-uuid]
  (if last-uuid
    (let [after (->> entries
                     (drop-while #(not= (:uuid %) last-uuid))
                     rest)]
      (if (seq after)
        (extract-messages after)
        (extract-messages entries)))
    (extract-messages entries)))

(let [input      (json/parse-string (slurp *in*) true)
      session-id (:session_id input)
      cwd        (:cwd input)
      base-url   (or (System/getenv "AI_MEMORY_URL") "http://localhost:8080")
      state-dir  (str (System/getenv "HOME") "/.claude/hooks/state")
      state-file (str state-dir "/" session-id ".edn")]

  (when session-id
    (when-let [transcript (or (:transcript_path input)
                              (find-transcript session-id))]
      (when (fs/exists? transcript)
        (let [last-uuid (when (fs/exists? state-file)
                          (:last-uuid (read-string (slurp state-file))))
              entries   (parse-jsonl transcript)
              messages  (delta-messages entries last-uuid)]

          (when (seq messages)
            (let [response
                  (try
                    (http/post (str base-url "/api/session/sync")
                               {:headers {"Content-Type" "application/json"}
                                :body    (json/generate-string
                                           {:session_id session-id
                                            :cwd        cwd
                                            :messages   messages})})
                    (catch Exception e
                      (binding [*out* *err*]
                        (println "session-sync: POST failed:" (.getMessage e)))
                      nil))

                  resp-body (when response
                              (try (json/parse-string (:body response) true)
                                   (catch Exception _ nil)))

                  chunk-bytes (or (:current-chunk-size resp-body)
                                  (:current_chunk_size resp-body)
                                  0)]

              (fs/create-dirs state-dir)
              (spit state-file
                    (pr-str {:last-uuid   (:uuid (last messages))
                             :chunk-bytes chunk-bytes}))

              ;; Write continuation cache so SessionStart (clear) can link sessions.
              ;; Stop fires after every agent turn, so cache is ready before /clear.
              (when-let [project (when cwd (last (str/split cwd #"/")))]
                (spit (str state-dir "/prev-session-" project ".edn")
                      (pr-str {:session-id session-id
                               :project    project}))))))))))

(System/exit 0)
