(ns ai-memory.mcp.protocol-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [cheshire.core :as json]
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

(defn- create-tagged-node! [conn content tag-paths]
  (let [uuid (d/squuid)]
    (doseq [p tag-paths]
      (tag/ensure-tag! conn p))
    (let [tag-refs  (mapv #(vector :tag/path %) tag-paths)
          count-txs (mapv (fn [ref] [:fn/inc-tag-count (second ref) 1]) tag-refs)]
      @(d/transact conn
         (into [{:db/id         (d/tempid :db.part/user)
                 :node/id       uuid
                 :node/content  content
                 :node/weight   1.0
                 :node/cycle    0
                 :node/tag-refs tag-refs}]
               count-txs)))
    uuid))

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
  (testing "returns all 9 tools with schemas"
    (let [resp  (call "tools/list")
          tools (get-in resp [:result :tools])]
      (is (= 9 (count tools)))
      (is (every? :name tools))
      (is (every? :description tools))
      (is (every? :inputSchema tools)))))

(deftest tools-list-names-test
  (testing "tool names match expected"
    (let [resp  (call "tools/list")
          names (set (map :name (get-in resp [:result :tools])))]
      (is (= #{"memory_browse_tags" "memory_count_facts" "memory_get_facts"
               "memory_search" "memory_create_tag" "memory_remember"
               "memory_list_blobs" "memory_read_blob"
               "memory_store_file"}
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

;; --- Render taxonomy ---

(deftest render-taxonomy-test
  (testing "renders tree as indented text"
    (let [tree [{:tag/name "lang" :node-count 142
                 :children [{:tag/name "clj" :node-count 87}
                            {:tag/name "py" :node-count 31}]}
                {:tag/name "pat" :node-count 95 :truncated true}]]
      (is (= "lang 142\n  clj 87\n  py 31\npat 95\n  ..."
             (protocol/render-taxonomy tree))))))

(deftest render-taxonomy-deep-test
  (testing "renders deep nesting with correct indentation"
    (let [tree [{:tag/name "lang" :node-count 100
                 :children [{:tag/name "clj" :node-count 50
                             :children [{:tag/name "core" :node-count 10
                                         :children [{:tag/name "async" :node-count 3}]}]}]}]]
      (is (= "lang 100\n  clj 50\n    core 10\n      async 3"
             (protocol/render-taxonomy tree))))))

(deftest render-taxonomy-truncated-leaf-test
  (testing "truncated leaf shows ... at child indent"
    (let [tree [{:tag/name "lang" :node-count 100
                 :children [{:tag/name "clj" :node-count 50 :truncated true}
                            {:tag/name "py" :node-count 30}]}]]
      (is (= "lang 100\n  clj 50\n    ...\n  py 30"
             (protocol/render-taxonomy tree))))))

(deftest render-counts-test
  (testing "renders counts as tag-set: N lines"
    (is (= "languages/clojure: 87\nlanguages/clojure + patterns/error-handling: 7"
           (protocol/render-counts
             [{:tags ["languages/clojure"] :count 87}
              {:tags ["languages/clojure" "patterns/error-handling"] :count 7}])))))

(deftest render-facts-test
  (testing "renders facts as plain text grouped by tag set"
    (let [text (protocol/render-facts
                 [{:tags ["languages/clojure"]
                   :facts [{:node/content "Use ex-info for errors"}
                           {:node/content "Prefer immutable data"}]}
                  {:tags ["languages/python"]
                   :facts [{:node/content "Use dataclasses"}]}])]
      (is (= (str "= languages/clojure\n"
                   "- Use ex-info for errors\n"
                   "- Prefer immutable data\n"
                   "\n"
                   "= languages/python\n"
                   "- Use dataclasses")
             text)))))

;; --- Tool dispatch with embedded HTTP server ---

(deftest browse-tags-tool-test
  (testing "memory_browse_tags returns indented text with 4 root tags"
    (let [resp (call "tools/call"
                 :params {:name "memory_browse_tags"
                          :arguments {:depth 1}})
          text (get-in resp [:result :content 0 :text])
          lines (str/split-lines text)]
      (is (nil? (:error resp)))
      (is (= 4 (count lines)))
      (is (every? #(re-matches #"\S+ \d+" %) lines)))))

(deftest browse-tags-subtree-test
  (testing "memory_browse_tags drills into a branch with indentation"
    (tag/ensure-tag! *conn* "languages/clojure")
    (tag/ensure-tag! *conn* "languages/python")
    (let [resp (call "tools/call"
                 :params {:name "memory_browse_tags"
                          :arguments {:path "languages" :depth 1}})
          text (get-in resp [:result :content 0 :text])
          lines (str/split-lines text)]
      (is (>= (count lines) 2))
      (is (some #(str/includes? % "clojure") lines)))))

(deftest browse-tags-nested-test
  (testing "deeper depth shows indented children"
    (tag/ensure-tag! *conn* "languages/clojure/core")
    (create-tagged-node! *conn* "Core fact" ["languages/clojure/core"])
    (let [resp (call "tools/call"
                 :params {:name "memory_browse_tags"
                          :arguments {:depth 3}})
          text (get-in resp [:result :content 0 :text])
          lines (str/split-lines text)]
      (is (some #(re-matches #"  \S+.*" %) lines))
      (is (some #(re-matches #"    \S+.*" %) lines)))))

(deftest browse-tags-truncated-test
  (testing "truncated branches show ... at child indent"
    (tag/ensure-tag! *conn* "languages/clojure/core")
    (let [resp (call "tools/call"
                 :params {:name "memory_browse_tags"
                          :arguments {:depth 2}})
          text (get-in resp [:result :content 0 :text])]
      (is (str/includes? text "clojure 0\n    ...")))))

(deftest count-facts-tool-test
  (testing "memory_count_facts returns text counts"
    (create-tagged-node! *conn* "A" ["languages/clojure" "patterns/error-handling"])
    (create-tagged-node! *conn* "B" ["languages/clojure"])
    (let [resp (call "tools/call"
                 :params {:name "memory_count_facts"
                          :arguments {:tag_sets [["languages/clojure"]
                                                 ["languages/clojure" "patterns/error-handling"]]}})
          text (get-in resp [:result :content 0 :text])
          lines (str/split-lines text)]
      (is (nil? (:error resp)))
      (is (= 2 (count lines)))
      (is (= "languages/clojure: 2" (first lines)))
      (is (= "languages/clojure + patterns/error-handling: 1" (second lines))))))

(deftest get-facts-tool-test
  (testing "memory_get_facts returns plain text facts"
    (create-tagged-node! *conn* "Clojure error handling"
                         ["languages/clojure" "patterns/error-handling"])
    (create-tagged-node! *conn* "Python basics" ["languages/python"])
    (let [resp (call "tools/call"
                 :params {:name "memory_get_facts"
                          :arguments {:tag_sets [["languages/clojure"]]
                                      :limit 10}})
          text (get-in resp [:result :content 0 :text])]
      (is (nil? (:error resp)))
      (is (str/includes? text "= languages/clojure"))
      (is (str/includes? text "- Clojure error handling"))
      (is (not (str/includes? text "Python basics"))))))

(deftest create-tag-tool-test
  (testing "memory_create_tag creates a new tag"
    (let [resp (call "tools/call"
                 :params {:name "memory_create_tag"
                          :arguments {:name "rust" :parent_path "lang"}})
          text (get-in resp [:result :content 0 :text])
          data (json/parse-string text true)]
      (is (nil? (:error resp)))
      (is (= "lang/rust" (:tag/path data))))))
