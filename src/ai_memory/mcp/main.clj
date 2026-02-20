(ns ai-memory.mcp.main
  "MCP thin client entry point. Connects to persistent ai-memory server via HTTP."
  (:require [ai-memory.mcp.protocol :as protocol]
            [ai-memory.mcp.transport :as transport]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main [& _args]
  (let [base-url  (or (System/getenv "AI_MEMORY_URL")
                      "http://localhost:8080")
        blob-path (or (System/getenv "BLOB_PATH")
                      (str (System/getProperty "user.home") "/.ai-memory/blobs"))]
    (log/info "ai-memory MCP client starting, server:" base-url "blobs:" blob-path)
    (transport/run-loop (protocol/make-handler base-url blob-path))))
