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
    :description "List all tags sorted by usage count. Returns one tag per line: `name count`."
    :inputSchema {:type       "object"
                  :properties {:limit  {:type        "integer"
                                        :description "Max tags to return (default 50)"
                                        :default     50}
                               :offset {:type        "integer"
                                        :description "Skip first N tags (default 0)"
                                        :default     0}}}}

   {:name        "memory_count_facts"
    :description "Count facts matching tag set intersections without fetching content. Send multiple tag sets to compare counts and decide which to fetch. Each tag set returns facts that have ALL listed tags."
    :inputSchema {:type       "object"
                  :properties {:tag_sets {:type        "array"
                                          :items       {:type "array" :items {:type "string"}}
                                          :description "Array of tag sets. Each set is an array of tag names."}}
                  :required   ["tag_sets"]}}

   {:name        "memory_get_facts"
    :description "Fetch facts matching filters. Each filter can combine: tags (intersection), query (semantic search), date range, limit. Returns one result group per filter."
    :inputSchema {:type       "object"
                  :properties {:filters  {:type        "array"
                                          :items       {:type       "object"
                                                        :properties {:id    {:type "integer"
                                                                             :description "Fetch a specific fact by entity ID (overrides other filters)"}
                                                                     :tags  {:type "array" :items {:type "string"}
                                                                             :description "Tag names — facts must have ALL (intersection)"}
                                                                     :query {:type "string"
                                                                             :description "Semantic search query"}
                                                                     :since {:type "string"
                                                                             :description "Date range start (ISO date, datetime, or relative: 7d, 2w, 1m, today, yesterday)"}
                                                                     :until {:type "string"
                                                                             :description "Date range end"}
                                                                     :limit {:type "integer"
                                                                             :description "Max results for this filter (default 50, or 10 if query)"}}}
                                          :description "Array of filters. Each filter is an independent query."}}
                  :required   ["filters"]}}

   {:name        "memory_create_tag"
    :description "Create a new tag. Tags are atomic strings (no hierarchy)."
    :inputSchema {:type       "object"
                  :properties {:name {:type        "string"
                                      :description "Tag name (e.g. 'clj', 'error-handling')"}}
                  :required   ["name"]}}

   {:name        "memory_remember"
    :description "Store memory facts. Facts are deduplicated and linked. Call after each meaningful turn."
    :inputSchema {:type       "object"
                  :properties {:nodes           {:type        "array"
                                                 :items       {:type       "object"
                                                               :properties {:content   {:type "string" :description "Fact content (1-3 sentences)"}
                                                                            :tags      {:type "array" :items {:type "string"} :description "Tag names"}}
                                                               :required   ["content" "tags"]}
                                                 :description "Memory nodes to store"}
                               :session_id      {:type        "string"
                                                 :description "Session ID for context-based linking across calls"}
                               :project         {:type        "string"
                                                 :description "Project name. Tags facts with this."}}}}

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
                               :tags    {:type "array" :items {:type "string"} :description "Tag names"}
                               :type    {:type "string"  :description "file, code, image, document"}
                               :content {:type ["string" "null"] :description "File content as text"}
                               :path    {:type ["string" "null"] :description "Absolute file path (server reads from disk)"}}
                  :required   ["title" "project" "summary" "tags"]}}

   {:name        "memory_session"
    :description "Update session metadata. Combine any of: summary (session arc), chunk_title (name current chunk), compact (detailed summary for /save). Call with whichever params are relevant — hook reminders will tell you which."
    :inputSchema {:type       "object"
                  :properties {:session_id {:type "string" :description "Session ID (same as session_id in memory_remember)"}
                               :project    {:type "string" :description "Project name"}
                               :summary    {:type "string" :description "Session arc summary: main topics in order, 1-3 sentences (e.g. 'Designed blob storage → implemented sync hook → debugged SSHFS mount')"}
                               :chunk_title {:type "string" :description "Short title for current conversation chunk (e.g. 'designed-blob-architecture'). Renames _current.md to a numbered file."}
                               :compact    {:type "string" :description "Detailed multi-paragraph session summary for /save. Stored as compact.md in the blob and becomes the searchable fact content."}}
                  :required   ["session_id"]}}

])

;; --- Compact text rendering ---

(defn render-tag-list
  "Renders flat tag list as text. One line per tag: `name count`."
  [tags]
  (if (empty? tags)
    "(no tags)"
    (str/join "\n" (map (fn [t] (str (:tag/name t) " " (or (:tag/node-count t) 0))) tags))))

(defn- tags-header [tags]
  (str/join " + " tags))

(defn render-counts
  "Renders count results as text. One line per tag set."
  [results]
  (str/join "\n" (map (fn [{:keys [tags count]}]
                        (str (tags-header tags) ": " count))
                      results)))

(defn- render-fact-line [blob-path fact]
  (let [eid      (:db/id fact)
        content  (:node/content fact)
        sources  (:node/sources fact)
        blob-dir (:node/blob-dir fact)
        refs     (cond-> []
                   (seq sources) (into (map #(str "src: " %) sources))
                   blob-dir      (conj (str "blob: " blob-path "/" blob-dir)))]
    (str "- "
         (when eid (str "[" eid "] "))
         content
         (when (seq refs) (str " [" (str/join ", " refs) "]")))))

(defn- format-date [inst]
  (when inst
    (subs (str inst) 0 10)))

(defn- render-scored-fact [fact]
  (let [eid     (:db/id fact)
        score   (:search/score fact)
        content (:node/content fact)
        tags    (->> (:node/tag-refs fact)
                     (map :tag/name)
                     (str/join ", "))]
    (str (format "%.2f" (double score))
         (when eid (str " [" eid "]"))
         " " content
         (when (seq tags) (str " [" tags "]")))))

(defn- filter-header [{:keys [id tags query]}]
  (let [parts (cond-> []
                id          (conj (str "id: " id))
                (seq tags)  (conj (tags-header tags))
                query       (conj (str "search: \"" query "\"")))]
    (if (seq parts) (str/join " | " parts) "all")))

(defn- render-one-group [blob-path {:keys [filter facts]}]
  (let [scored? (some :search/score facts)
        header  (filter-header filter)]
    (str "= " header "\n"
         (if scored?
           (str/join "\n" (map render-scored-fact facts))
           (str/join "\n" (map (partial render-fact-line blob-path) facts))))))

(defn render-filter-results
  "Renders unified filter results. Adapts format per group: scored for query results, plain for tag results."
  [blob-path results]
  (if (empty? results)
    "(no results)"
    (str/join "\n\n" (map (partial render-one-group blob-path) results))))

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
    (str (or (:title meta) (:session-id meta) "Blob") "\n"
         (name (or (:type meta) :unknown)) " | " (:project meta) " | " (format-date (:created-at meta)) "\n"
         (when-let [ss (:session-summary meta)]
           (str "\nSession summary: " ss "\n"))
         (when-let [cs (:compact-summary meta)]
           (str "\n--- Compact summary ---\n" cs "\n-----------------------\n"))
         (when (and (not (:compact-summary meta)) (:summary meta) (seq (:summary meta)))
           (str "\n" (:summary meta)))
         (when (:status meta)
           (str "\nStatus: " (:status meta)))
         (when-let [n (:turn-count meta)]
           (str "\n\nTurns: " n " (use section param 0.." (dec n) " to read)"))
         (when-let [n (:line-count meta)]
           (str "\nLines: " n))
         ;; Legacy: section-count for non-session blobs (memory_store_file)
         (when (and (not (:turn-count meta)) (:section-count meta))
           (str "\n\nSections: " (:section-count meta)
                " (use section param 0.." (dec (:section-count meta)) " to read)")))))



;; --- Parameter conversion (snake_case JSON → kebab-case Clojure) ---

(defn- convert-params
  "Explicit per-tool parameter conversion. Not a generic deep transform."
  [handler-key params]
  (case handler-key
    :browse-tags {:limit  (or (:limit params) 50)
                  :offset (or (:offset params) 0)}
    :count-facts {:tag-sets (:tag_sets params)}
    :get-facts   {:filters (:filters params)}
    :create-tag  {:name (:name params)}
    :remember    {:nodes      (mapv (fn [n]
                                      {:content (:content n)
                                       :tags    (:tags n)})
                                    (:nodes params))
                  :context-id (:session_id params)
                  :project    (:project params)}
    :list-blobs  {:limit (or (:limit params) 20)}
    :read-blob   {:blob-dir (:blob_dir params)
                  :section  (:section params)}
    :store-file      {:title   (:title params)
                      :project (:project params)
                      :summary (:summary params)
                      :tags    (:tags params)
                      :type    (:type params)
                      :content (:content params)
                      :path    (:path params)}
    :session         {:session-id   (:session_id params)
                      :project      (:project params)
                      :summary      (:summary params)
                      :chunk-title  (:chunk_title params)
                      :compact      (:compact params)}
    params))

;; --- Handler dispatch ---

(defn- call-tool [base-url handler-key params]
  (case handler-key
    :browse-tags        (server/handle-browse-tags base-url params)
    :count-facts        (server/handle-count-facts base-url params)
    :get-facts          (server/handle-get-facts base-url params)
    :create-tag         (server/handle-create-tag base-url params)
    :remember           (server/handle-remember base-url params)
    :list-blobs         (server/handle-list-blobs base-url params)
    :read-blob          (server/handle-read-blob base-url params)
    :store-file         (server/handle-store-file base-url params)
    :session            (server/handle-session base-url params)))

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
   "memory_create_tag"           :create-tag
   "memory_remember"             :remember
   "memory_list_blobs"           :list-blobs
   "memory_read_blob"            :read-blob
   "memory_store_file"           :store-file
   "memory_session"              :session})

(defn- format-result [blob-path handler-key result]
  (case handler-key
    :browse-tags (render-tag-list result)
    :count-facts (render-counts result)
    :get-facts   (render-filter-results blob-path result)
    :list-blobs  (render-blob-list result)
    :read-blob   (if (:content result)
                   (:content result)
                   (render-blob-meta result))
    (json/generate-string result)))

(defn- handle-tools-call [base-url blob-path id params]
  (let [tool-name (:name params)
        arguments (or (:arguments params) {})]
    (if-let [handler-key (get handler-keys tool-name)]
      (let [converted (convert-params handler-key arguments)
            result    (call-tool base-url handler-key converted)]
        {:jsonrpc "2.0"
         :id      id
         :result  {:content [{:type "text"
                              :text (format-result blob-path handler-key result)}]}})
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    -32602
                 :message (str "Unknown tool: " tool-name)}})))

;; --- Top-level dispatch ---

(defn make-handler
  "Returns a request→response fn closed over base-url and blob-path."
  [base-url blob-path]
  (fn [{:keys [id method params]}]
    (log/debug "MCP request:" method)
    (case method
      "initialize"                (handle-initialize id)
      "notifications/initialized" nil
      "tools/list"                (handle-tools-list id)
      "tools/call"                (handle-tools-call base-url blob-path id params)
      "ping"                      (handle-ping id)
      ;; Unknown method
      (if id
        {:jsonrpc "2.0"
         :id      id
         :error   {:code    -32601
                   :message (str "Method not found: " method)}}
        nil))))
