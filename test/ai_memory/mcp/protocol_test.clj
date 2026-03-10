(ns ai-memory.mcp.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai-memory.mcp.protocol :as protocol]))

;; --- Pure rendering tests (no DB) ---

(deftest initialize-test
  (testing "returns protocol version and capabilities"
    (let [handler (protocol/make-handler {:base-url "http://localhost:8080"})
          resp    (handler {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})]
      (is (= 1 (:id resp)))
      (is (= "2025-03-26" (get-in resp [:result :protocolVersion])))
      (is (= "ai-memory" (get-in resp [:result :serverInfo :name])))
      (is (some? (get-in resp [:result :capabilities :tools]))))))

(deftest tools-list-test
  (testing "returns all tools with schemas"
    (let [handler (protocol/make-handler {:base-url "http://localhost:8080"})
          resp    (handler {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}})
          tools   (get-in resp [:result :tools])]
      (is (every? :name tools))
      (is (every? :description tools))
      (is (every? :inputSchema tools)))))

(deftest tools-list-names-test
  (testing "tool names match expected"
    (let [handler (protocol/make-handler {:base-url "http://localhost:8080"})
          resp    (handler {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}})
          names   (set (map :name (get-in resp [:result :tools])))]
      (is (= #{"memory_explore_tags" "memory_resolve_tags"
               "memory_get_facts"
               "memory_remember" "memory_reinforce"
               "memory_read_blob"
               "memory_session" "memory_project"}
             names)))))

(deftest ping-test
  (testing "returns empty result"
    (let [handler (protocol/make-handler {:base-url "http://localhost:8080"})
          resp    (handler {:jsonrpc "2.0" :id 1 :method "ping" :params {}})]
      (is (= {} (:result resp))))))

(deftest notification-returns-nil-test
  (testing "notifications/initialized returns nil"
    (let [handler (protocol/make-handler {:base-url "http://localhost:8080"})
          resp    (handler {:jsonrpc "2.0" :method "notifications/initialized"})]
      (is (nil? resp)))))

(deftest unknown-method-test
  (testing "unknown method returns -32601"
    (let [handler (protocol/make-handler {:base-url "http://localhost:8080"})
          resp    (handler {:jsonrpc "2.0" :id 1 :method "nonexistent" :params {}})]
      (is (= -32601 (get-in resp [:error :code]))))))

(deftest unknown-tool-test
  (testing "unknown tool returns -32602"
    (let [handler (protocol/make-handler {:base-url "http://localhost:8080"})
          resp    (handler {:jsonrpc "2.0" :id 1 :method "tools/call"
                            :params {:name "nonexistent_tool" :arguments {}}})]
      (is (= -32602 (get-in resp [:error :code]))))))

;; --- Render tag list ---

(deftest render-tag-list-test
  (testing "renders untiered tags under dynamic: header"
    (let [tags [{:tag/name "clj" :tag/node-count 87}
                {:tag/name "python" :tag/node-count 31}
                {:tag/name "preference" :tag/node-count 12}]]
      (is (= "dynamic:\nclj 87\npython 31\npreference 12"
             (protocol/render-tag-list tags)))))
  (testing "renders tiered + untiered tags grouped by section"
    (let [tags [{:tag/name "architecture" :tag/node-count 63 :tag/tier :aspect}
                {:tag/name "debugging" :tag/node-count 21 :tag/tier :aspect}
                {:tag/name "clj" :tag/node-count 5}]]
      (is (= "aspect:\narchitecture 63\ndebugging 21\ndynamic:\nclj 5"
             (protocol/render-tag-list tags))))))

(deftest render-tag-list-empty-test
  (testing "renders empty tag list"
    (is (= "(no tags)" (protocol/render-tag-list [])))))

(deftest render-tag-list-nil-count-test
  (testing "renders tags with nil count as 0"
    (let [tags [{:tag/name "new-tag" :tag/node-count nil}]]
      (is (= "dynamic:\nnew-tag 0" (protocol/render-tag-list tags))))))

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

(deftest render-filter-results-empty-filter-test
  (testing "render-filter-results handles filter with no tags/query"
    (let [text (protocol/render-filter-results
                 [{:filter {}
                   :facts [{:db/id 201 :node/content "Some fact"}]}])]
      (is (= "= all\n- [201] Some fact" text)))))
