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

   {:name        "memory_search"
    :description "Semantic search across all facts by meaning. Use when you can't find relevant facts via tags, or when you know roughly what you're looking for but not how it's tagged. Returns facts ranked by relevance score."
    :inputSchema {:type       "object"
                  :properties {:query {:type        "string"
                                       :description "Natural language search query"}
                               :top_k {:type        "integer"
                                       :description "Max results (default 10)"
                                       :default     10}}
                  :required   ["query"]}}

   {:name        "memory_create_tag"
    :description "Create a new tag in the taxonomy. Provide parent_path to nest under an existing tag, or omit for a root tag."
    :inputSchema {:type       "object"
                  :properties {:name        {:type        "string"
                                             :description "Tag leaf name (e.g. 'clojure')"}
                               :parent_path {:type        "string"
                                             :description "Parent tag path (e.g. 'languages'). Omit for root tag."}}
                  :required   ["name"]}}

   {:name        "memory_remember"
    :description "Store memory facts and/or a turn summary. Facts are deduplicated and linked. Turn summary is stored as a conversation record. Call after each message with at least a turn_summary."
    :inputSchema {:type       "object"
                  :properties {:nodes        {:type        "array"
                                              :items       {:type       "object"
                                                            :properties {:content   {:type "string" :description "Fact content (1-3 sentences)"}
                                                                         :tags      {:type "array" :items {:type "string"} :description "Tag paths"}}
                                                            :required   ["content" "tags"]}
                                              :description "Memory nodes to store"}
                               :turn_summary     {:type        "string"
                                                  :description "One-line turn summary: 'User: <request> → <what I did>'"}
                               :session_summary  {:type        "string"
                                                  :description "Rolling 1-sentence session summary (updated each turn). Stored as searchable fact."}
                               :context_id       {:type        "string"
                                                  :description "Session ID for context-based linking across calls"}}}}

   {:name        "memory_list_blobs"
    :description "List stored blobs (conversations, documents) sorted by date desc. Returns compact text: one line per blob with date, dir, summary."
    :inputSchema {:type       "object"
                  :properties {:limit {:type    "integer"
                                       :description "Max results (default 20)"
                                       :default 20}}}}

   {:name        "memory_read_blob"
    :description "Read blob metadata or a specific section. Call with blob_dir only to see summary + section index. Call with blob_dir + section (0-based) to get full section content."
    :inputSchema {:type       "object"
                  :properties {:blob_dir {:type "string"
                                          :description "Blob directory name (e.g. '2026-02-17_blob-storage-design')"}
                               :section  {:type ["integer" "null"]
                                          :description "Section index to read (null = metadata only)"}}
                  :required   ["blob_dir"]}}

   {:name        "memory_store_file"
    :description "Store a file (code, document, image) as a blob. Provide content directly or a file path."
    :inputSchema {:type       "object"
                  :properties {:title   {:type "string"  :description "Descriptive name"}
                               :project {:type "string"  :description "Project name"}
                               :summary {:type "string"  :description "What this file is, 1-2 sentences"}
                               :tags    {:type "array" :items {:type "string"} :description "Tag paths"}
                               :type    {:type "string"  :description "file, code, image, document"}
                               :content {:type ["string" "null"] :description "File content as text"}
                               :path    {:type ["string" "null"] :description "Absolute file path (server reads from disk)"}}
                  :required   ["title" "project" "summary" "tags"]}}])

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

(defn- render-fact-line [fact]
  (let [content (:node/content fact)
        sources (:node/sources fact)]
    (if (seq sources)
      (str "- " content " [src: " (str/join ", " sources) "]")
      (str "- " content))))

(defn render-facts
  "Renders facts grouped by tag set as plain text. Shows source indicator for facts with blob refs."
  [results]
  (str/join "\n\n"
    (map (fn [{:keys [tags facts]}]
           (str "= " (tags-header tags) "\n"
                (str/join "\n" (map render-fact-line facts))))
         results)))

(defn- format-date [inst]
  (when inst
    (subs (str inst) 0 10)))

(defn render-search-results
  "Renders vector search results as text. Score + content + tags per fact."
  [results]
  (if (empty? results)
    "(no matches)"
    (str/join "\n"
      (map (fn [node]
             (let [score   (:search/score node)
                   content (:node/content node)
                   tags    (->> (:node/tag-refs node)
                                (map :tag/path)
                                (str/join ", "))]
               (str (format "%.2f" (double score)) " " content
                    (when (seq tags) (str " [" tags "]")))))
           results))))

(defn render-blob-list
  "Renders blob list as compact text. One line per blob."
  [blobs]
  (if (empty? blobs)
    "(no blobs)"
    (str/join "\n"
      (map (fn [b]
             (let [date    (format-date (:node/created-at b))
                   dir     (:node/blob-dir b)
                   content (:node/content b)]
               (str date " blob " dir "\n  " content)))
           blobs))))

(defn render-blob-meta
  "Renders blob metadata as readable text."
  [meta]
  (if (:error meta)
    (:error meta)
    (str (:title meta) "\n"
         (name (or (:type meta) :unknown)) " | " (:project meta) " | " (format-date (:created-at meta)) "\n"
         "\n"
         (:summary meta)
         (when (:status meta)
           (str "\n\nStatus: " (:status meta)))
         "\n\nSections:"
         (str/join ""
           (map-indexed
             (fn [i s]
               (str "\n  " i ": " (:summary s) " (" (or (:lines s) "?") " lines)"))
             (:sections meta))))))

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
    :search      {:query (:query params)
                  :top-k (or (:top_k params) 10)}
    :create-tag  {:name        (:name params)
                  :parent-path (:parent_path params)}
    :remember    {:nodes           (mapv (fn [n]
                                        {:content (:content n)
                                         :tags    (:tags n)})
                                      (:nodes params))
                  :turn-summary    (:turn_summary params)
                  :session-summary (:session_summary params)
                  :context-id      (:context_id params)}
    :list-blobs  {:limit (or (:limit params) 20)}
    :read-blob   {:blob-dir (:blob_dir params)
                  :section  (:section params)}
    :store-file  {:title   (:title params)
                  :project (:project params)
                  :summary (:summary params)
                  :tags    (:tags params)
                  :type    (:type params)
                  :content (:content params)
                  :path    (:path params)}
    params))

;; --- Handler dispatch ---

(defn- call-tool [base-url handler-key params]
  (case handler-key
    :browse-tags        (server/handle-browse-tags base-url params)
    :count-facts        (server/handle-count-facts base-url params)
    :get-facts          (server/handle-get-facts base-url params)
    :search             (server/handle-search-facts base-url params)
    :create-tag         (server/handle-create-tag base-url params)
    :remember           (server/handle-remember base-url params)
    :list-blobs         (server/handle-list-blobs base-url params)
    :read-blob          (server/handle-read-blob base-url params)
    :store-file         (server/handle-store-file base-url params)))

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
  {"memory_browse_tags"          :browse-tags
   "memory_count_facts"          :count-facts
   "memory_get_facts"            :get-facts
   "memory_search"               :search
   "memory_create_tag"           :create-tag
   "memory_remember"             :remember
   "memory_list_blobs"           :list-blobs
   "memory_read_blob"            :read-blob
   "memory_store_file"           :store-file})

(defn- format-result [handler-key result]
  (case handler-key
    :browse-tags (render-taxonomy result)
    :count-facts (render-counts result)
    :get-facts   (render-facts result)
    :search      (render-search-results result)
    :list-blobs  (render-blob-list result)
    :read-blob   (if (:content result)
                   (:content result)
                   (render-blob-meta result))
    (json/generate-string result)))

(defn- handle-tools-call [base-url id params]
  (let [tool-name (:name params)
        arguments (or (:arguments params) {})]
    (if-let [handler-key (get handler-keys tool-name)]
      (let [converted (convert-params handler-key arguments)
            result    (call-tool base-url handler-key converted)]
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
  "Returns a request→response fn closed over base-url."
  [base-url]
  (fn [{:keys [id method params]}]
    (log/debug "MCP request:" method)
    (case method
      "initialize"                (handle-initialize id)
      "notifications/initialized" nil
      "tools/list"                (handle-tools-list id)
      "tools/call"                (handle-tools-call base-url id params)
      "ping"                      (handle-ping id)
      ;; Unknown method
      (if id
        {:jsonrpc "2.0"
         :id      id
         :error   {:code    -32601
                   :message (str "Method not found: " method)}}
        nil))))
