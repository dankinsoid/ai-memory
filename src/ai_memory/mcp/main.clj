(ns ai-memory.mcp.main
  "MCP thin client entry point. Connects to persistent ai-memory server via HTTP."
  (:require [ai-memory.mcp.protocol :as protocol]
            [ai-memory.mcp.transport :as transport]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main [& _args]
  (let [cfg {:base-url  (or (System/getenv "AI_MEMORY_URL")
                            "http://localhost:8080")
             :api-token (System/getenv "AI_MEMORY_TOKEN")}]
    (log/info "ai-memory MCP client starting, server:" (:base-url cfg))
    (transport/run-loop (protocol/make-handler cfg))))
