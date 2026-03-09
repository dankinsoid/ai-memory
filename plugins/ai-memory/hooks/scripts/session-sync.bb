#!/usr/bin/env bb
;; Stop hook: syncs conversation transcript delta to server.
;;
;; Reads JSONL transcript, computes delta since last sync,
;; POSTs to /api/session/sync, saves state (last-uuid + chunk-bytes).
;;
;; State file: ~/.claude/hooks/state/{session-id}.edn
;;   {:last-uuid "..." :chunk-bytes 12345}
;;
;; Env-var toggles (set any to disable):
;;   AI_MEMORY_DISABLED=1     — master switch (all hooks)
;;   AI_MEMORY_NO_WRITE=1     — disable all writes
;;   AI_MEMORY_NO_SESSIONS=1  — disable session-specific features

(when (or (System/getenv "AI_MEMORY_DISABLED")
          (System/getenv "AI_MEMORY_NO_WRITE")
          (System/getenv "AI_MEMORY_NO_SESSIONS"))
  (System/exit 0))

(require '[cheshire.core :as json]
         '[babashka.http-client :as http]
         '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(defn git-sh [cwd & args]
  (try
    (let [result (apply process/sh "git" "-C" cwd args)]
      (when (zero? (:exit result))
        (let [out (str/trim (:out result))]
          (when-not (str/blank? out) out))))
    (catch Exception _ nil)))

(defn git-project-name [cwd]
  (when cwd
    (some-> (git-sh cwd "remote" "get-url" "origin")
            (str/replace #"\.git$" "")
            (str/split #"[/:]")
            last)))

(defn git-context [cwd]
  (when cwd
    (let [branch (git-sh cwd "rev-parse" "--abbrev-ref" "HEAD")
          commit (git-sh cwd "rev-parse" "--short" "HEAD")
          remote (git-sh cwd "remote" "get-url" "origin")]
      (when commit
        (cond-> {:end_commit commit}
          (and branch (not= "HEAD" branch)) (assoc :branch branch)
          remote                             (assoc :remote remote))))))

(defn derive-project [cwd]
  (or (git-project-name cwd)
      (when cwd (last (str/split cwd #"/")))))

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

(def api-token (System/getenv "AI_MEMORY_TOKEN"))

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
              messages  (delta-messages entries last-uuid)
              project   (derive-project cwd)
              git       (git-context cwd)]

          (when (seq messages)
            (let [response
                  (try
                    (http/post (str base-url "/api/session/sync")
                               {:headers (cond-> {"Content-Type" "application/json"}
                                           api-token (assoc "Authorization" (str "Bearer " api-token)))
                                :body    (json/generate-string
                                           (cond-> {:session_id session-id
                                                    :cwd        cwd
                                                    :messages   messages}
                                             project (assoc :project project)
                                             git     (assoc :git git)))})
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

              ;; prev-session cache is written by session-end.bb (SessionEnd/clear only).
              ;; Stop hook must NOT write it — parallel sessions would overwrite each other.
              )))))))

(System/exit 0)
