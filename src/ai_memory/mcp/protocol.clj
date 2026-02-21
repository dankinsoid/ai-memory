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
  [{:name        "memory_explore_tags"
    :description "Explore tag space: list all tags with usage counts, or count facts for specific tag set intersections. Omit tag_sets to browse all tags. Provide tag_sets to count intersection sizes."
    :inputSchema {:type       "object"
                  :properties {:tag_sets {:type        "array"
                                          :items       {:type "array" :items {:type "string"}}
                                          :description "Array of tag sets. Each set is an array of tag names. Returns count of facts matching ALL tags in each set."}
                               :limit    {:type        "integer"
                                          :description "Max tags to return when browsing (default 50). Ignored when tag_sets provided."
                                          :default     50}
                               :offset   {:type        "integer"
                                          :description "Skip first N tags when browsing (default 0). Ignored when tag_sets provided."
                                          :default     0}}}}

   {:name        "memory_get_facts"
    :description "Fetch facts matching filters. Each filter can combine: tags (intersection), query (semantic search), date range, limit. Returns one result group per filter."
    :inputSchema {:type       "object"
                  :properties {:filters  {:type        "array"
                                          :items       {:type       "object"
                                                        :properties {:id         {:type "integer"
                                                                                  :description "Fetch a specific fact by entity ID (overrides other filters)"}
                                                                     :session_id {:type "string"
                                                                                  :description "Fetch session fact by session ID"}
                                                                     :tags  {:type "array" :items {:type "string"}
                                                                             :description "Tag names — facts must have ALL (intersection)"}
                                                                     :query {:type "string"
                                                                             :description "Semantic search query"}
                                                                     :since {:type "string"
                                                                             :description "Date range start (ISO date, datetime, or relative: 7d, 2w, 1m, today, yesterday)"}
                                                                     :until {:type "string"
                                                                             :description "Date range end"}
                                                                     :limit {:type "integer"
                                                                             :description "Max results for this filter (default 50, or 10 if query)"}
                                                                     :sort_by {:type "string"
                                                                               :enum ["weight" "date"]
                                                                               :description "Sort order: weight (decayed importance, default) or date (newest first)"
                                                                               :default "weight"}
                                                                     :offset {:type "integer"
                                                                              :description "Skip first N results for pagination (default 0)"
                                                                              :default 0}}}
                                          :description "Array of filters. Each filter is an independent query."}}
                  :required   ["filters"]}}

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


;; --- Parameter conversion (snake_case JSON → kebab-case Clojure) ---

(defn- convert-params
  "Explicit per-tool parameter conversion. Not a generic deep transform."
  [handler-key params]
  (case handler-key
    :explore-tags {:tag-sets (:tag_sets params)
                   :limit    (or (:limit params) 50)
                   :offset   (or (:offset params) 0)}
    :get-facts   {:filters (:filters params)}
    :remember    {:nodes      (mapv (fn [n]
                                      {:content (:content n)
                                       :tags    (:tags n)})
                                    (:nodes params))
                  :context-id (:session_id params)
                  :project    (:project params)}
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
    :explore-tags       (server/handle-explore-tags base-url params)
    :get-facts          (server/handle-get-facts base-url params)
    :remember           (server/handle-remember base-url params)
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
  {"memory_explore_tags"          :explore-tags
   "memory_get_facts"            :get-facts
   "memory_remember"             :remember
   "memory_store_file"           :store-file
   "memory_session"              :session})

(defn- format-result [blob-path handler-key result]
  (case handler-key
    :explore-tags (case (:mode result)
                    :browse (render-tag-list (:data result))
                    :count  (render-counts (:data result)))
    :get-facts   (render-filter-results blob-path result)
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
