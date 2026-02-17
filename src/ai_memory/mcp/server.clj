(ns ai-memory.mcp.server
  "MCP server for agent memory access.
   Tools: browse-tags, count-facts, get-facts, remember, create-tag."
  (:require [ai-memory.graph.write :as write]
            [ai-memory.tag.core :as tag]
            [ai-memory.tag.query :as tag-query]
            [ai-memory.db.core :as db]))

;; TODO: implement MCP protocol (JSON-RPC over stdio/SSE)
;; For now, define the tool schemas and handlers.

(defn handle-browse-tags
  "Navigates tag taxonomy with depth-limited tree.
   `path`  — root of subtree (nil = full tree from roots)
   `depth` — max levels to return (default 2)"
  [conn _cfg {:keys [path depth] :or {depth 2}}]
  (let [db (db/db conn)]
    (tag-query/taxonomy db path depth)))

(defn handle-count-facts
  "Counts facts for each tag set (intersection). No entity pulls.
   `tag-sets` — [[\"lang/clj\" \"pat/err\"] [\"lang/py\"]]"
  [conn cfg {:keys [tag-sets]}]
  (let [db (db/db conn)]
    (tag-query/count-by-tag-sets db (:metrics cfg) tag-sets)))

(defn handle-get-facts
  "Fetches facts for each tag set (intersection) with per-set limit.
   `tag-sets` — [[\"lang/clj\" \"pat/err\"] [\"lang/py\"]]
   `limit`   — max facts per tag set (default 50)"
  [conn cfg {:keys [tag-sets limit] :or {limit 50}}]
  (let [db (db/db conn)]
    (tag-query/fetch-by-tag-sets db (:metrics cfg) tag-sets {:limit limit})))

(defn handle-create-tag
  "Creates a new tag in the taxonomy."
  [conn {:keys [name parent-path]}]
  (let [path (tag/create-tag! conn {:name name :parent-path parent-path})]
    {:tag/path path}))

(defn handle-remember
  "Stores memory nodes with automatic associations and deduplication."
  [conn cfg params]
  (write/remember conn cfg params))
