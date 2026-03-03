#!/usr/bin/env bb
;; Migrates session blobs from flat structure to project subdirectories.
;;
;; Before: data/blobs/2026-03-03_session-abc12345/
;; After:  data/blobs/projects/{project}/2026-03-03_session-abc12345/
;;
;; Reads meta.edn from each session blob to determine the project.
;; Blobs without a project go to projects/_unknown/.
;; Non-session blobs are left in place.
;;
;; Usage: BLOB_PATH=/path/to/blobs bb scripts/migrate-blob-dirs.bb [--dry-run]

(require '[babashka.fs :as fs]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def blob-path (or (System/getenv "BLOB_PATH")
                   (str (System/getenv "HOME") "/.ai-memory/blobs")))

(def dry-run? (some #{"--dry-run"} *command-line-args*))

(defn log [& args] (apply println args))

(when dry-run?
  (log "DRY RUN — no files will be moved"))

(log "Blob path:" blob-path)

(let [base    (fs/file blob-path)
      dirs    (->> (fs/list-dir base)
                   (filter fs/directory?)
                   (remove #(= "projects" (fs/file-name %)))
                   (filter #(str/includes? (fs/file-name %) "session"))
                   sort)
      _       (log (str "Found " (count dirs) " session blob dirs to migrate"))
      results (atom {:moved 0 :skipped 0 :errors 0})]

  (doseq [dir dirs]
    (let [dir-name  (fs/file-name dir)
          meta-file (fs/file dir "meta.edn")]
      (if-not (fs/exists? meta-file)
        (do (log "SKIP (no meta.edn):" dir-name)
            (swap! results update :skipped inc))
        (try
          (let [meta    (edn/read-string (slurp (str meta-file)))
                project (or (:project meta) "_unknown")
                dest    (fs/file blob-path "projects" project dir-name)]
            (if (fs/exists? dest)
              (do (log "SKIP (already exists):" (str "projects/" project "/" dir-name))
                  (swap! results update :skipped inc))
              (do (log "MOVE:" dir-name "→" (str "projects/" project "/" dir-name))
                  (when-not dry-run?
                    (fs/create-dirs (fs/parent dest))
                    (fs/move dir dest))
                  (swap! results update :moved inc))))
          (catch Exception e
            (log "ERROR:" dir-name (.getMessage e))
            (swap! results update :errors inc))))))

  (log "\nResults:" @results))
