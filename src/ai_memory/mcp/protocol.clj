(ns ai-memory.mcp.protocol
  "MCP JSON-RPC 2.0 protocol: tool registry, method dispatch, parameter conversion."
  (:require [ai-memory.mcp.server :as server]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def protocol-version "2024-11-05")

(def server-info
  {:name    "ai-memory"
   :version "0.1.0"})

(def server-capabilities
  {:tools {}})

;; --- Tool definitions ---

(def tools
  [{:name        "memory_browse_tags"
    :description "Navigate the tag taxonomy tree. Returns indented text: each line is `name count`, nesting via 2-space indent. `...` as child means deeper levels exist (drill down with path). Reconstruct full paths by joining ancestor names with `/`. Start with path=null to see root categories."
    :inputSchema {:type       "object"
                  :properties {:path  {:type        ["string" "null"]
                                       :description "Root path to browse from (null = full tree from roots)"}
                               :depth {:type        "integer"
                                       :description "Max tree depth to return (default 2)"
                                       :default     2}}}}

   {:name        "memory_count_facts"
    :description "Count facts matching tag set intersections without fetching content. Send multiple tag sets to compare counts and decide which to fetch. Each tag set returns facts that have ALL listed tags."
    :inputSchema {:type       "object"
                  :properties {:tag_sets {:type        "array"
                                          :items       {:type "array" :items {:type "string"}}
                                          :description "Array of tag sets. Each set is an array of tag paths."}}
                  :required   ["tag_sets"]}}

   {:name        "memory_get_facts"
    :description "Fetch facts matching tag set intersections. Returns fact content, all tags, and metadata grouped by queried tag set."
    :inputSchema {:type       "object"
                  :properties {:tag_sets {:type        "array"
                                          :items       {:type "array" :items {:type "string"}}
                                          :description "Array of tag sets. Each set is an array of tag paths."}
                               :limit    {:type        "integer"
                                          :description "Max facts per tag set (default 50)"
                                          :default     50}}
                  :required   ["tag_sets"]}}

   {:name        "memory_create_tag"
    :description "Create a new tag in the taxonomy. Provide parent_path to nest under an existing tag, or omit for a root tag."
    :inputSchema {:type       "object"
                  :properties {:name        {:type        "string"
                                             :description "Tag leaf name (e.g. 'clojure')"}
                               :parent_path {:type        "string"
                                             :description "Parent tag path (e.g. 'languages'). Omit for root tag."}}
                  :required   ["name"]}}

   {:name        "memory_remember"
    :description "Store memory facts. Each node has content (1-3 sentences), optional type, and tags. Automatically creates associations between co-occurring facts and deduplicates."
    :inputSchema {:type       "object"
                  :properties {:nodes      {:type        "array"
                                            :items       {:type       "object"
                                                          :properties {:content   {:type "string" :description "Fact content (1-3 sentences)"}
                                                                       :node_type {:type "string" :description "fact, decision, preference, pattern, project, domain, entity"}
                                                                       :tags      {:type "array" :items {:type "string"} :description "Tag paths"}}
                                                          :required   ["content" "tags"]}
                                            :description "Memory nodes to store"}
                               :context_id {:type        "string"
                                            :description "Session ID for context-based linking across calls"}}
                  :required   ["nodes"]}}])

;; --- Compact text rendering ---

(defn- render-taxonomy-node [node depth]
  (let [indent   (apply str (repeat (* 2 depth) \space))
        line     (str indent (:tag/name node) " " (:node-count node 0))
        ellipsis (when (:truncated node)
                   (str (apply str (repeat (* 2 (inc depth)) \space)) "..."))]
    (cond
      (seq (:children node))
      (str line "\n" (str/join "\n" (map #(render-taxonomy-node % (inc depth)) (:children node))))

      ellipsis
      (str line "\n" ellipsis)

      :else line)))

(defn render-taxonomy
  "Renders taxonomy tree as indented text. Agent reconstructs paths from hierarchy.
   `...` as child means truncated — deeper levels exist. Example:
   languages 142\\n  clojure 87\\n    ...\\n  python 31"
  [tree]
  (str/join "\n" (map #(render-taxonomy-node % 0) tree)))

(defn- tags-header [tags]
  (str/join " + " tags))

(defn render-counts
  "Renders count results as text. One line per tag set."
  [results]
  (str/join "\n" (map (fn [{:keys [tags count]}]
                        (str (tags-header tags) ": " count))
                      results)))

(defn render-facts
  "Renders facts grouped by tag set as plain text. Content only, no metadata."
  [results]
  (str/join "\n\n"
    (map (fn [{:keys [tags facts]}]
           (str "= " (tags-header tags) "\n"
                (str/join "\n" (map #(str "- " (:node/content %)) facts))))
         results)))

;; --- Parameter conversion (snake_case JSON → kebab-case Clojure) ---

(defn- convert-params
  "Explicit per-tool parameter conversion. Not a generic deep transform."
  [handler-key params]
  (case handler-key
    :browse-tags {:path  (:path params)
                  :depth (or (:depth params) 2)}
    :count-facts {:tag-sets (:tag_sets params)}
    :get-facts   {:tag-sets (:tag_sets params)
                  :limit    (or (:limit params) 50)}
    :create-tag  {:name        (:name params)
                  :parent-path (:parent_path params)}
    :remember    {:nodes      (mapv (fn [n]
                                      {:content   (:content n)
                                       :node-type (:node_type n)
                                       :tags      (:tags n)})
                                    (:nodes params))
                  :context-id (:context_id params)}
    params))

;; --- Handler dispatch ---

(defn- call-tool [conn cfg handler-key params]
  (case handler-key
    :browse-tags (server/handle-browse-tags conn cfg params)
    :count-facts (server/handle-count-facts conn cfg params)
    :get-facts   (server/handle-get-facts conn cfg params)
    :create-tag  (server/handle-create-tag conn params)
    :remember    (server/handle-remember conn cfg params)))

;; --- JSON-RPC method handlers ---

(defn- handle-initialize [id]
  {:jsonrpc "2.0"
   :id      id
   :result  {:protocolVersion protocol-version
             :capabilities    server-capabilities
             :serverInfo      server-info}})

(defn- handle-tools-list [id]
  {:jsonrpc "2.0"
   :id      id
   :result  {:tools (mapv #(select-keys % [:name :description :inputSchema]) tools)}})

(defn- handle-ping [id]
  {:jsonrpc "2.0"
   :id      id
   :result  {}})

(def ^:private tool-by-name
  (into {} (map (juxt :name (fn [t] (:handler-key t)))) tools))

(def ^:private handler-keys
  {"memory_browse_tags" :browse-tags
   "memory_count_facts" :count-facts
   "memory_get_facts"   :get-facts
   "memory_create_tag"  :create-tag
   "memory_remember"    :remember})

(defn- format-result [handler-key result]
  (case handler-key
    :browse-tags (render-taxonomy result)
    :count-facts (render-counts result)
    :get-facts   (render-facts result)
    (json/generate-string result)))

(defn- handle-tools-call [conn cfg id params]
  (let [tool-name (:name params)
        arguments (or (:arguments params) {})]
    (if-let [handler-key (get handler-keys tool-name)]
      (let [converted (convert-params handler-key arguments)
            result    (call-tool conn cfg handler-key converted)]
        {:jsonrpc "2.0"
         :id      id
         :result  {:content [{:type "text"
                              :text (format-result handler-key result)}]}})
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    -32602
                 :message (str "Unknown tool: " tool-name)}})))

;; --- Top-level dispatch ---

(defn make-handler
  "Returns a request→response fn closed over conn and cfg."
  [conn cfg]
  (fn [{:keys [id method params]}]
    (log/debug "MCP request:" method)
    (case method
      "initialize"                (handle-initialize id)
      "notifications/initialized" nil
      "tools/list"                (handle-tools-list id)
      "tools/call"                (handle-tools-call conn cfg id params)
      "ping"                      (handle-ping id)
      ;; Unknown method
      (if id
        {:jsonrpc "2.0"
         :id      id
         :error   {:code    -32601
                   :message (str "Method not found: " method)}}
        nil))))
