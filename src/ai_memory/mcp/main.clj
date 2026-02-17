(ns ai-memory.mcp.main
  "MCP server entry point. Boots Datomic + stdio loop, no HTTP."
  (:require [ai-memory.config :as config]
            [ai-memory.db.core :as db]
            [ai-memory.metrics :as metrics]
            [ai-memory.mcp.protocol :as protocol]
            [ai-memory.mcp.transport :as transport]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main [& _args]
  (let [config   (config/load-config)
        registry (metrics/create-registry)
        cfg      (assoc config :metrics registry)
        conn     (db/connect (:datomic-uri cfg))]
    (db/ensure-schema conn)
    (log/info "ai-memory MCP server starting")
    (transport/run-loop (protocol/make-handler conn cfg))))
