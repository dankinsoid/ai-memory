#!/usr/bin/env bb
;; Migrates data from the remote ai-memory server to local Datalevin.
;; Usage: bb scripts/migrate-from-server.bb [server-url]
;;
;; Example: bb scripts/migrate-from-server.bb http://46.101.153.18:8080

(require '[clojure.string :as str]
         '[babashka.curl :as curl]
         '[cheshire.core :as json])

(def server-url (or (first *command-line-args*) "http://46.101.153.18:8080"))

(println "Migrating from server:" server-url)

;; Fetch all nodes
(println "Fetching nodes...")
(let [resp    (curl/get (str server-url "/api/nodes") {:query-params {"limit" "10000"}})
      nodes   (-> resp :body (json/parse-string true) :nodes)
      tags    (-> (curl/get (str server-url "/api/tags")) :body (json/parse-string true))
      export  {:nodes nodes :tags tags}
      outfile "/tmp/ai-memory-migration.edn"]
  (spit outfile (pr-str export))
  (println "Fetched" (count nodes) "nodes," (count tags) "tags")
  (println "Saved to" outfile)
  (println "Now run: clj -M:run -- --import" outfile))
