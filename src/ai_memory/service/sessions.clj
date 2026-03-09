;; @ai-generated(guided)
(ns ai-memory.service.sessions
  "Domain operations on sessions: sync turns, update summaries, continuation chains, projects.
   Orchestrates FactStore, VectorStore, blob filesystem, and tag creation."
  (:require [ai-memory.store.protocols :as p]
            [ai-memory.service.tags :as tags]
            [ai-memory.graph.node :as node]
            [ai-memory.blob.store :as blob-store]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util Date UUID]
           [java.time Instant]))

;; --- Internal helpers ---

(defn- embed-node! [stores eid content]
  (try
    (let [vector (p/embed-document (:embedding stores) content)]
      (p/upsert! (:vector-store stores) eid vector {}))
    (catch Exception e
      (log/warn e "Failed to embed session node" eid))))

(defn- find-session-fact
  "Finds existing session node by session-id. Returns entity id or nil."
  [fact-store session-id]
  (when session-id
    (when-let [node (p/find-nodes-by-session fact-store session-id)]
      (:db/id node))))

(defn- link-blob-dir! [fact-store eid blob-dir]
  (p/set-node-blob-dir! fact-store eid blob-dir))

(defn upsert-session-fact!
  "Creates or updates the rolling session summary fact.
   Ensures tags, embeds content, creates/updates node.
   `stores`          — map with :fact-store, :vector-store, :tag-vector-store, :embedding
   `session-id`      — session UUID string
   `session-summary` — summary text
   `project`         — project name or nil
   `extra-tags`      — additional tags or nil"
  [stores session-id session-summary project extra-tags]
  (let [fs       (:fact-store stores)
        eid      (find-session-fact fs session-id)
        tag-strs (distinct (concat ["session"]
                                   (when project [(str "project/" project)])
                                   extra-tags))
        tag-vec  (mapv str/trim tag-strs)]
    (tags/ensure! stores tag-vec)
    (if eid
      (do
        (p/update-node-content! fs eid session-summary)
        (embed-node! stores eid session-summary)
        (p/update-node-tags! fs eid tag-vec))
      (let [result  (p/create-node! fs {:content    session-summary
                                         :tags       tag-vec
                                         :session-id session-id})
            new-eid (:id result)]
        (embed-node! stores new-eid session-summary)))))

;; --- Project facts ---

(defn- find-project-fact
  "Finds existing project node by project name. Returns entity id or nil."
  [fact-store project]
  (when-let [nodes (p/find-nodes-by-tags fact-store
                                          ["project" (str "project/" project)]
                                          nil)]
    (:db/id (first nodes))))

(defn upsert-project-fact!
  "Creates or updates a project summary fact.
   `stores`  — map with :fact-store, :vector-store, :tag-vector-store, :embedding
   `project` — project name
   `summary` — project summary text
   `tags`    — additional tags"
  [stores project summary extra-tags]
  (let [fs       (:fact-store stores)
        eid      (find-project-fact fs project)
        tag-strs (mapv str/trim (distinct (concat ["project" (str "project/" project)]
                                                  extra-tags)))]
    (tags/ensure! stores tag-strs)
    (if eid
      (do
        (p/update-node-content! fs eid summary)
        (embed-node! stores eid summary)
        (p/update-node-tags! fs eid tag-strs))
      (let [result  (p/create-node! fs {:content summary :tags tag-strs})
            new-eid (:id result)]
        (embed-node! stores new-eid summary)))))

(defn project-update!
  "Updates project summary fact.
   `stores`  — stores map
   `project` — project name
   `summary` — summary text
   `tags`    — additional tags"
  [stores project summary tags]
  (upsert-project-fact! stores project summary (or tags [])))

;; --- Session sync (turns → blob) ---

(defn- parse-instant [s]
  (when s (Instant/parse s)))

(defn- has-user-text?
  "Returns true if a message has actual user text (not just tool_result blocks)."
  [{:keys [type content]}]
  (and (= "user" type)
       (if (sequential? content)
         (some (fn [block]
                 (and (= "text" (:type block))
                      (not (str/blank? (:text block)))))
               content)
         (and (string? content) (not (str/blank? content))))))

(defn- group-into-turns
  "Groups messages into turns. A turn starts with a user text message."
  [messages]
  (when (seq messages)
    (let [groups (reduce (fn [acc msg]
                           (if (has-user-text? msg)
                             (conj acc [msg])
                             (if (seq acc)
                               (update acc (dec (count acc)) conj msg)
                               (conj acc [msg]))))
                         [] messages)]
      (mapv (fn [msgs]
              {:messages msgs
               :t-start  (parse-instant (:timestamp (first msgs)))
               :t-end    (parse-instant (:timestamp (last msgs)))})
            groups))))

(defn- extract-text [content]
  (if (string? content)
    content
    (->> content
         (filter #(= "text" (:type %)))
         (map :text)
         (remove str/blank?)
         (str/join "\n"))))

(def ^:private injected-tag-names
  ["system-reminder" "system_reminder" "system_instruction"
   "local-command-stdout" "local-command-caveat"
   "ide_selection" "ide_file" "ide_opened_file"
   "user-prompt-submit-hook"])

(def ^:private injected-tags-re
  (re-pattern (str "(?s)<(" (str/join "|" injected-tag-names) ")>.*?</\\1>")))

(defn- strip-injected-tags
  "Strips known injected XML-style context blocks from message text."
  [s]
  (when s
    (-> s
        (str/replace injected-tags-re "")
        str/trim)))

(defn- format-turn-as-markdown [messages]
  (->> messages
       (keep (fn [{:keys [role content]}]
               (let [text (strip-injected-tags (extract-text content))]
                 (when-not (str/blank? text)
                   (str "**" (or role "unknown") "**\n" text)))))
       (str/join "\n\n")))

(defn- extract-first-user-prompt [messages]
  (when-let [first-user (first (filter has-user-text? messages))]
    (let [text (strip-injected-tags (extract-text (:content first-user)))
          max-len 120]
      (when-not (str/blank? text)
        (if (<= (count text) max-len)
          text
          (str (subs text 0 max-len) "..."))))))

(defn- derive-project [cwd]
  (when cwd
    (last (str/split cwd #"/"))))

(defn- merge-git-context [existing-git new-git]
  (when new-git
    (let [start  (or (:start-commit existing-git) (:end-commit new-git))
          remote (or (:remote new-git) (:remote existing-git))]
      (cond-> new-git
        start  (assoc :start-commit start)
        remote (assoc :remote remote)))))

(defn sync!
  "Syncs conversation turns to blob storage, creates/links session node.
   `stores`    — stores map
   `blob-path` — filesystem base path
   `data`      — {:session-id, :cwd, :project, :messages, :git}"
  [stores blob-path data]
  (let [fs         (:fact-store stores)
        session-id (:session-id data)
        cwd        (:cwd data)
        messages   (:messages data)
        project    (or (:project data) (derive-project cwd))
        turns      (group-into-turns messages)

        session-eid  (find-session-fact fs session-id)
        datomic-dir  (when session-eid
                       (:node/blob-dir (p/find-node fs session-eid)))
        blob-dir     (or (when datomic-dir
                           (or (blob-store/resolve-blob-dir blob-path datomic-dir)
                               datomic-dir))
                         (blob-store/find-session-blob-dir blob-path session-id)
                         (blob-store/make-blob-dir-name
                           blob-path (str "session-" (subs session-id 0 8))
                           :project project))
        blob-dir-short (blob-store/blob-dir-name blob-dir)

        existing-meta (blob-store/read-meta blob-path blob-dir)
        turn-offset   (or (:turn-count existing-meta) 0)

        new-turn-texts
        (->> turns
             (keep-indexed
               (fn [i {:keys [messages t-start]}]
                 (let [content (format-turn-as-markdown messages)]
                   (when-not (str/blank? content)
                     (let [turn-num (+ turn-offset i 1)]
                       (str "## Turn " turn-num
                            (when t-start (str " (" t-start ")"))
                            "\n" content "\n\n"))))))
             vec)

        append-result (when (seq new-turn-texts)
                        (blob-store/append-current-chunk!
                          blob-path blob-dir (str/join new-turn-texts)))

        total-turns (+ turn-offset (count new-turn-texts))

        session-summary (when session-eid
                          (:node/content (p/find-node fs session-eid)))

        git-context (merge-git-context (:git existing-meta) (:git data))

        meta-data (merge
                    (or existing-meta
                        (cond-> {:id         (UUID/randomUUID)
                                 :type       :session
                                 :created-at (Date.)
                                 :session-id session-id}
                          project (assoc :project project)))
                    {:turn-count total-turns}
                    (when (and project (not (:project existing-meta)))
                      {:project project})
                    (when session-summary
                      {:session-summary session-summary})
                    (when git-context
                      {:git git-context}))]
    (blob-store/write-meta! blob-path blob-dir meta-data)

    ;; Create or link session node
    (if session-eid
      (do
        (when-not datomic-dir
          (link-blob-dir! fs session-eid blob-dir-short))
        (when project
          (let [proj-tag (str "project/" project)]
            (tags/ensure! stores [proj-tag])
            (p/update-node-tags! fs session-eid [proj-tag]))))
      (let [session-tags (cond-> ["session"]
                           project (conj (str "project/" project)))]
        (tags/ensure! stores session-tags)
        (let [content (or session-summary
                          (extract-first-user-prompt messages)
                          "Session conversation")
              result  (p/create-node! fs {:content    content
                                          :tags       session-tags
                                          :blob-dir   blob-dir-short
                                          :session-id session-id})
              new-eid (:id result)]
          (embed-node! stores new-eid content))))

    {:blob-dir           blob-dir-short
     :turns-added        (count new-turn-texts)
     :total-turns        total-turns
     :current-chunk-size (or (:byte-count append-result) 0)}))

(defn update!
  "Updates session summary, chunk naming, and/or compact summary.
   `stores`    — stores map
   `blob-path` — filesystem base path
   `data`      — {:session-id, :project, :title, :summary, :tags, :chunk-title, :compact}"
  [stores blob-path data]
  (let [fs          (:fact-store stores)
        session-id  (:session-id data)
        project     (:project data)
        title       (:title data)
        summary     (:summary data)
        sess-tags   (:tags data)
        chunk-title (:chunk-title data)
        compact     (:compact data)

        session-eid (find-session-fact fs session-id)
        datomic-dir (when session-eid
                      (:node/blob-dir (p/find-node fs session-eid)))
        blob-dir    (or (when datomic-dir
                          (or (blob-store/resolve-blob-dir blob-path datomic-dir)
                              datomic-dir))
                        (blob-store/find-session-blob-dir blob-path session-id)
                        (let [dir-name (blob-store/make-blob-dir-name
                                         blob-path (str "session-" (subs session-id 0 8))
                                         :project project)]
                          (blob-store/write-meta! blob-path dir-name
                            (cond-> {:id         (UUID/randomUUID)
                                     :type       :session
                                     :created-at (Date.)
                                     :session-id session-id}
                              project (assoc :project project)))
                          dir-name))
        blob-dir-short (blob-store/blob-dir-name blob-dir)

        ;; Link blob-dir to existing node if not yet linked
        _ (when (and session-eid (not datomic-dir))
            (link-blob-dir! fs session-eid blob-dir-short))

        ;; 1. Update session summary
        summary-result
        (when summary
          (let [content (if title (str title "\n" summary) summary)]
            (upsert-session-fact! stores session-id content project sess-tags))
          ;; If node was just created by upsert, link blob-dir to it
          (when-not session-eid
            (when-let [new-eid (find-session-fact fs session-id)]
              (link-blob-dir! fs new-eid blob-dir-short)))
          (let [meta (blob-store/read-meta blob-path blob-dir)]
            (when meta
              (blob-store/write-meta! blob-path blob-dir
                (assoc meta :session-summary summary))))
          (if datomic-dir "updated" "created"))

        ;; 1b. Update title in blob meta
        _ (when title
            (let [meta (blob-store/read-meta blob-path blob-dir)]
              (when meta
                (blob-store/write-meta! blob-path blob-dir
                  (assoc meta :title title)))))

        ;; Re-read session-eid after potential upsert
        session-eid (or session-eid (find-session-fact fs session-id))

        ;; 2. Name current chunk
        chunk-result
        (when chunk-title
          (or (blob-store/rename-current-chunk! blob-path blob-dir chunk-title)
              "no _current.md to rename"))

        ;; 3. Compact summary
        compact-result
        (when (and compact session-eid)
          (blob-store/write-section! blob-path blob-dir "compact.md" compact)
          (node/embed-file! (:vector-store stores) (:embedding stores)
                            session-eid blob-dir-short "compact.md" compact)
          "stored")]

    (cond-> {:blob-dir blob-dir-short}
      summary-result (assoc :summary summary-result)
      chunk-result   (assoc :chunk chunk-result)
      compact-result (assoc :compact compact-result))))

;; --- Continuation chain ---

(defn continue!
  "Links two sessions via a continuation edge (new → prev).
   Self-heals: creates prev session fact if it doesn't exist yet.
   `stores`          — stores map
   `prev-session-id` — previous session UUID
   `session-id`      — current session UUID
   `project`         — project name or nil"
  [stores prev-session-id session-id project]
  (let [fs       (:fact-store stores)
        prev-eid (or (find-session-fact fs prev-session-id)
                     (do (upsert-session-fact! stores prev-session-id
                           (str "session " (subs prev-session-id 0 8)) project nil)
                         (find-session-fact fs prev-session-id)))]
    (when-not prev-eid
      (throw (ex-info "Failed to create prev session fact" {:prev-session-id prev-session-id})))
    (when-not (find-session-fact fs session-id)
      (upsert-session-fact! stores session-id
        (str "continuation of " prev-session-id) project nil))
    (let [new-eid  (find-session-fact fs session-id)
          prev-dir (:node/blob-dir (p/find-node fs prev-eid))]
      (p/find-or-create-edge! fs new-eid prev-eid 0.5 {:type :continuation})
      (cond-> {:status "linked" :edge-created true}
        prev-dir (assoc :prev-blob-dir prev-dir)))))

(defn- traverse-continuation-chain
  "Follows :continuation edges backward, returns chain entries."
  [fact-store start-eid max-depth]
  (loop [eid   start-eid
         chain []
         depth 0]
    (if (>= depth max-depth)
      chain
      (if-let [edge-data (p/find-typed-edge-from fact-store eid :continuation)]
        (let [prev     (:edge/to edge-data)
              prev-eid (:db/id prev)]
          (recur prev-eid
                 (conj chain {:eid         prev-eid
                              :session-id  (:node/session-id prev)
                              :blob-dir    (:node/blob-dir prev)
                              :content     (:node/content prev)
                              :edge-id     (:edge/id edge-data)
                              :edge-weight (:edge/weight edge-data)})
                 (inc depth)))
        chain))))

(defn chain
  "Traverses continuation chain from a session. Optionally strengthens first edge.
   `stores`     — stores map
   `session-id` — session UUID
   `strengthen` — if true, promotes first continuation edge to eternal"
  [stores session-id strengthen]
  (let [fs  (:fact-store stores)
        eid (find-session-fact fs session-id)]
    (if-not eid
      []
      (let [chain (traverse-continuation-chain fs eid 10)]
        (when (and strengthen (seq chain))
          (p/promote-edge-eternal! fs (:edge-id (first chain))))
        (mapv #(dissoc % :eid) chain)))))
