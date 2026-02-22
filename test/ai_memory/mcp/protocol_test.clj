(ns ai-memory.mcp.protocol-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [datomic.api :as d]
            [ai-memory.db.core :as db]
            [ai-memory.tag.core :as tag]
            [ai-memory.web.handler :as web]
            [ai-memory.mcp.protocol :as protocol]))

(def ^:dynamic *conn* nil)
(def ^:dynamic *base-url* nil)
(def ^:dynamic *server* nil)

(defn- test-uri []
  (str "datomic:mem://mcp-protocol-test-" (d/squuid)))

(defn with-server [f]
  (let [uri  (test-uri)
        conn (db/connect uri)
        _    (db/ensure-schema conn)
        cfg  {:metrics nil :blob-path "/tmp/ai-memory-test-blobs"}
        srv  (web/start {:port 0 :conn conn :cfg cfg})
        port (-> srv .getConnectors first .getLocalPort)]
    (binding [*conn*     conn
              *base-url* (str "http://localhost:" port)
              *server*   srv]
      (try
        (f)
        (finally
          (.stop srv)
          (d/delete-database uri))))))

(use-fixtures :each with-server)

;; --- Helpers ---

(defn- create-tagged-node!
  ([conn content tag-names] (create-tagged-node! conn content tag-names nil))
  ([conn content tag-names opts]
   (let [now    (java.util.Date.)
         tempid (d/tempid :db.part/user)]
     (doseq [n tag-names]
       (tag/ensure-tag! conn n))
     (let [tag-refs  (mapv #(vector :tag/name %) tag-names)
           count-txs (mapv (fn [ref] [:fn/inc-tag-count (second ref) 1]) tag-refs)
           node-map  {:db/id           tempid
                      :node/content    content
                      :node/weight     1.0
                      :node/cycle      0
                      :node/tag-refs   tag-refs
                      :node/created-at now
                      :node/updated-at (or (:updated-at opts) now)}
           tx @(d/transact conn (into [node-map] count-txs))]
       (d/resolve-tempid (:db-after tx) (:tempids tx) tempid)))))

(defn- handler []
  (protocol/make-handler *base-url*))

(defn- call [method & {:keys [id params] :or {id 1 params {}}}]
  ((handler) {:jsonrpc "2.0" :id id :method method :params params}))

;; --- Protocol tests (no DB) ---

(deftest initialize-test
  (testing "returns protocol version and capabilities"
    (let [resp (call "initialize")]
      (is (= 1 (:id resp)))
      (is (= "2024-11-05" (get-in resp [:result :protocolVersion])))
      (is (= "ai-memory" (get-in resp [:result :serverInfo :name])))
      (is (some? (get-in resp [:result :capabilities :tools]))))))

(deftest tools-list-test
  (testing "returns all tools with schemas"
    (let [resp  (call "tools/list")
          tools (get-in resp [:result :tools])]
      (is (every? :name tools))
      (is (every? :description tools))
      (is (every? :inputSchema tools)))))

(deftest tools-list-names-test
  (testing "tool names match expected"
    (let [resp  (call "tools/list")
          names (set (map :name (get-in resp [:result :tools])))]
      (is (= #{"memory_explore_tags" "memory_get_facts"
               "memory_remember" "memory_reinforce"
               "memory_store_file" "memory_session"}
             names)))))

(deftest ping-test
  (testing "returns empty result"
    (let [resp (call "ping")]
      (is (= {} (:result resp))))))

(deftest notification-returns-nil-test
  (testing "notifications/initialized returns nil"
    (let [resp ((handler) {:jsonrpc "2.0" :method "notifications/initialized"})]
      (is (nil? resp)))))

(deftest unknown-method-test
  (testing "unknown method returns -32601"
    (let [resp (call "nonexistent")]
      (is (= -32601 (get-in resp [:error :code]))))))

(deftest unknown-tool-test
  (testing "unknown tool returns -32602"
    (let [resp (call "tools/call" :params {:name "nonexistent_tool" :arguments {}})]
      (is (= -32602 (get-in resp [:error :code]))))))

;; --- Render tag list ---

(deftest render-tag-list-test
  (testing "renders flat tag list as name count lines"
    (let [tags [{:tag/name "clj" :tag/node-count 87}
                {:tag/name "python" :tag/node-count 31}
                {:tag/name "pref" :tag/node-count 12}]]
      (is (= "clj 87\npython 31\npref 12"
             (protocol/render-tag-list tags))))))

(deftest render-tag-list-empty-test
  (testing "renders empty tag list"
    (is (= "(no tags)" (protocol/render-tag-list [])))))

(deftest render-tag-list-nil-count-test
  (testing "renders tags with nil count as 0"
    (let [tags [{:tag/name "new-tag" :tag/node-count nil}]]
      (is (= "new-tag 0" (protocol/render-tag-list tags))))))

(deftest render-counts-test
  (testing "renders counts as tag-set: N lines"
    (is (= "clj: 87\nclj + error-handling: 7"
           (protocol/render-counts
             [{:tags ["clj"] :count 87}
              {:tags ["clj" "error-handling"] :count 7}])))))

(deftest render-filter-results-test
  (testing "renders filter results with entity IDs"
    (let [text (protocol/render-filter-results
                 [{:filter {:tags ["clj"]}
                   :facts [{:db/id 101 :node/content "Use ex-info for errors"}
                           {:db/id 102 :node/content "Prefer immutable data"}]}
                  {:filter {:tags ["python"]}
                   :facts [{:db/id 103 :node/content "Use dataclasses"}]}])]
      (is (= (str "= clj\n"
                   "- [101] Use ex-info for errors\n"
                   "- [102] Prefer immutable data\n"
                   "\n"
                   "= python\n"
                   "- [103] Use dataclasses")
             text)))))

;; --- Tool dispatch with embedded HTTP server ---

(deftest explore-tags-browse-test
  (testing "memory_explore_tags without tag_sets returns flat tag list"
    (create-tagged-node! *conn* "Fact 1" ["clj"])
    (create-tagged-node! *conn* "Fact 2" ["clj"])
    (create-tagged-node! *conn* "Fact 3" ["python"])
    (let [resp (call "tools/call"
                 :params {:name "memory_explore_tags"
                          :arguments {:limit 50}})
          text (get-in resp [:result :content 0 :text])
          lines (str/split-lines text)]
      (is (nil? (:error resp)))
      (is (= 2 (count lines)))
      (is (every? #(re-matches #"\S+ \d+" %) lines))
      ;; Sorted by count desc: clj 2 first
      (is (str/starts-with? (first lines) "clj")))))

(deftest explore-tags-empty-test
  (testing "memory_explore_tags with no tags returns (no tags)"
    (let [resp (call "tools/call"
                 :params {:name "memory_explore_tags"
                          :arguments {}})
          text (get-in resp [:result :content 0 :text])]
      (is (= "(no tags)" text)))))

(deftest explore-tags-count-test
  (testing "memory_explore_tags with tag_sets returns text counts"
    (create-tagged-node! *conn* "A" ["clj" "error-handling"])
    (create-tagged-node! *conn* "B" ["clj"])
    (let [resp (call "tools/call"
                 :params {:name "memory_explore_tags"
                          :arguments {:tag_sets [["clj"]
                                                 ["clj" "error-handling"]]}})
          text (get-in resp [:result :content 0 :text])
          lines (str/split-lines text)]
      (is (nil? (:error resp)))
      (is (= 2 (count lines)))
      (is (= "clj: 2" (first lines)))
      (is (= "clj + error-handling: 1" (second lines))))))

(deftest get-facts-tool-test
  (testing "memory_get_facts returns plain text facts"
    (create-tagged-node! *conn* "Clojure error handling"
                         ["clj" "error-handling"])
    (create-tagged-node! *conn* "Python basics" ["python"])
    (let [resp (call "tools/call"
                 :params {:name "memory_get_facts"
                          :arguments {:filters [{:tags ["clj"] :limit 10}]}})
          text (get-in resp [:result :content 0 :text])]
      (is (nil? (:error resp)))
      (is (str/includes? text "= clj"))
      (is (str/includes? text "Clojure error handling"))
      (is (re-find #"\[(\d+)\]" text) "should include entity ID")
      (is (not (str/includes? text "Python basics"))))))

(deftest get-facts-with-since-test
  (testing "memory_get_facts with since filters by date"
    (let [old-date (java.util.Date. (- (System/currentTimeMillis) (* 30 24 60 60 1000)))
          new-date (java.util.Date. (- (System/currentTimeMillis) (* 1 24 60 60 1000)))]
      (create-tagged-node! *conn* "Old fact" ["clj"] {:updated-at old-date})
      (create-tagged-node! *conn* "New fact" ["clj"] {:updated-at new-date})
      (let [resp (call "tools/call"
                   :params {:name "memory_get_facts"
                            :arguments {:filters [{:tags ["clj"] :since "7d"}]}})
            text (get-in resp [:result :content 0 :text])]
        (is (nil? (:error resp)))
        (is (str/includes? text "New fact"))
        (is (not (str/includes? text "Old fact")))))))

(deftest get-facts-no-tags-test
  (testing "memory_get_facts with date-only filter"
    (let [old-date (java.util.Date. (- (System/currentTimeMillis) (* 30 24 60 60 1000)))
          new-date (java.util.Date. (- (System/currentTimeMillis) (* 1 24 60 60 1000)))]
      (create-tagged-node! *conn* "Old fact" ["clj"] {:updated-at old-date})
      (create-tagged-node! *conn* "New fact" ["python"] {:updated-at new-date})
      (let [resp (call "tools/call"
                   :params {:name "memory_get_facts"
                            :arguments {:filters [{:since "7d"}]}})
            text (get-in resp [:result :content 0 :text])]
        (is (nil? (:error resp)))
        (is (str/includes? text "= all"))
        (is (str/includes? text "New fact"))
        (is (not (str/includes? text "Old fact")))))))

(deftest render-filter-results-empty-filter-test
  (testing "render-filter-results handles filter with no tags/query"
    (let [text (protocol/render-filter-results
                 [{:filter {}
                   :facts [{:db/id 201 :node/content "Some fact"}]}])]
      (is (= "= all\n- [201] Some fact" text)))))

