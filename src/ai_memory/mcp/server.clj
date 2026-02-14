(ns ai-memory.mcp.server
  "MCP server for agent memory access.
   Tools: recall, remember, reinforce."
  (:require [ai-memory.graph.node :as node]
            [ai-memory.graph.traverse :as traverse]
            [ai-memory.db.core :as db]))

;; TODO: implement MCP protocol (JSON-RPC over stdio/SSE)
;; For now, define the tool schemas and handlers.

(defn handle-recall
  "Recalls relevant memories for a given query."
  [conn {:keys [query k depth-strategy]}]
  (let [db            (db/db conn)
        ;; TODO: vector search to find entry nodes
        entry-nodes   []
        current-cycle 0] ;; TODO: track cycles
    (traverse/recall db {:entry-nodes   entry-nodes
                         :k             (or k 10)
                         :current-cycle current-cycle})))

(defn handle-remember
  "Stores a new memory node with associations."
  [conn {:keys [content node-type scope associations]}]
  (node/create-node conn {:content   content
                          :node-type (or node-type :fact)
                          :scope     (or scope :node.scope/global)})
  ;; TODO: create edges to associated nodes
  )
