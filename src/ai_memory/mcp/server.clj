(ns ai-memory.mcp.server
  "MCP server for agent memory access.
   Tools: recall (tag-based), remember, create-tag."
  (:require [ai-memory.graph.write :as write]
            [ai-memory.tag.core :as tag]
            [ai-memory.tag.query :as tag-query]
            [ai-memory.db.core :as db]))

;; TODO: implement MCP protocol (JSON-RPC over stdio/SSE)
;; For now, define the tool schemas and handlers.

(defn handle-recall
  "Recalls memories via tags.
   Modes:
     :browse — taxonomy navigation (returns tag tree with counts)
     :tags   — tag intersection (returns nodes matching ALL tags)
     :search — vector search (TODO: integrate with embeddings)"
  [conn _cfg {:keys [mode tags path]}]
  (let [db (db/db conn)]
    (case mode
      :browse  (tag-query/browse db path)
      :tags    (tag-query/by-tags db {:tags tags})
      :subtree (tag-query/by-subtree db (first tags))
      ;; default: tag intersection if tags provided
      (if (seq tags)
        (tag-query/by-tags db {:tags tags})
        (tag-query/browse db nil)))))

(defn handle-create-tag
  "Creates a new tag in the taxonomy."
  [conn {:keys [name parent-path]}]
  (let [path (tag/create-tag! conn {:name name :parent-path parent-path})]
    {:tag/path path}))

(defn handle-remember
  "Stores memory nodes with automatic associations and deduplication.
   `params`:
     :nodes      — vec of {:content, :node-type, :tags}
     :context-id — optional session/conversation ID"
  [conn cfg params]
  (write/remember conn cfg params))
