(ns ai-memory.mcp.server
  "MCP server for agent memory access.
   Tools: recall, remember, reinforce."
  (:require [ai-memory.graph.traverse :as traverse]
            [ai-memory.graph.write :as write]
            [ai-memory.db.core :as db]))

;; TODO: implement MCP protocol (JSON-RPC over stdio/SSE)
;; For now, define the tool schemas and handlers.

(defn handle-recall
  "Recalls relevant memories for a given query."
  [conn {:keys [query k depth-strategy]}]
  (let [db            (db/db conn)
        ;; TODO: vector search to find entry nodes
        entry-nodes   []
        current-tick  (db/current-tick db)]
    (traverse/recall db {:entry-nodes   entry-nodes
                         :k             (or k 10)
                         :current-cycle current-tick})))

(defn handle-remember
  "Stores memory nodes with automatic associations and deduplication.
   `params`:
     :nodes      — vec of {:content, :node-type, :tags}
     :context-id — optional session/conversation ID"
  [conn cfg params]
  (write/remember conn cfg params))
