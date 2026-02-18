(ns ai-memory.mcp.main
  "MCP server entry point. Boots Datomic + HTTP (for hooks) + stdio loop."
  (:require [ai-memory.config :as config]
            [ai-memory.db.core :as db]
            [ai-memory.metrics :as metrics]
            [ai-memory.mcp.protocol :as protocol]
            [ai-memory.mcp.transport :as transport]
            [ai-memory.web.handler :as web]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main [& _args]
  (let [config   (config/load-config)
        registry (metrics/create-registry)
        cfg      (assoc config :metrics registry)
        conn     (db/connect (:datomic-uri cfg))]
    (db/ensure-schema conn)
    ;; HTTP server in background (for Stop hook sync)
    (web/start {:port (:port cfg) :conn conn :cfg cfg})
    (log/info "ai-memory MCP+HTTP server starting, HTTP port" (:port cfg))
    ;; MCP stdio loop blocks main thread
    (transport/run-loop (protocol/make-handler conn cfg))))
