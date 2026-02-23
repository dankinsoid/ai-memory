#!/usr/bin/env bb
;; Load skill script: discovers session continuation chain and strengthens edge.
;;
;; Usage: bb load-chain.bb <session-id>
;;
;; Calls POST /api/session/chain to traverse continuation edges backward,
;; strengthens the first edge to max weight (expressing /load intent),
;; and outputs the chain for the agent to read blobs from.

(require '[cheshire.core :as json]
         '[babashka.http-client :as http])

(def base-url (or (System/getenv "AI_MEMORY_URL") "http://localhost:8080"))
(def blob-mount (or (System/getenv "AI_MEMORY_BLOB_MOUNT") "~/.ai-memory/blobs"))

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

(let [session-id (first *command-line-args*)]
  (when-not session-id
    (println "Usage: bb load-chain.bb <session-id>")
    (System/exit 1))

  (let [result (api-post "/api/session/chain"
                 {:session_id session-id
                  :strengthen true})
        chain  (:chain result)]
    (if (empty? chain)
      (println "No continuation chain found for session" session-id)
      (do
        (println "## Session Chain")
        (println)
        (doseq [{:keys [session-id blob-dir content edge-weight]} chain]
          (println (str "- " (or content "(no summary)")
                        (when blob-dir (str " [blob: " blob-dir "]"))
                        (when session-id (str " {" session-id "}"))
                        (when edge-weight (str " (edge: " edge-weight ")")))))
        (println)
        (println "## Load Order")
        (println "Read compact.md from each previous session (newest first):")
        (println)
        (doseq [{:keys [blob-dir]} chain
                :when blob-dir]
          (println (str "- " blob-mount "/" blob-dir "/compact.md")))
        (println)
        (println "Edge to immediate predecessor strengthened to max weight.")))))

(System/exit 0)
