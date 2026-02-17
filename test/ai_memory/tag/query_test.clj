(ns ai-memory.tag.query-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [ai-memory.db.core :as db]
            [ai-memory.tag.core :as tag]
            [ai-memory.tag.query :as query]))

(def ^:dynamic *conn* nil)

(defn- test-uri []
  (str "datomic:mem://tag-query-test-" (d/squuid)))

(defn with-datomic [f]
  (let [uri  (test-uri)
        conn (db/connect uri)]
    (db/ensure-schema conn)
    (binding [*conn* conn]
      (try
        (f)
        (finally
          (d/delete-database uri))))))

(use-fixtures :each with-datomic)

;; --- Helpers ---

(defn- create-tagged-node!
  "Creates a node with tag refs for testing."
  [conn content tag-paths]
  (let [uuid (d/squuid)]
    (doseq [p tag-paths]
      (tag/ensure-tag! conn p))
    @(d/transact conn
       [{:db/id         (d/tempid :db.part/user)
         :node/id       uuid
         :node/content  content
         :node/type     :node.type/fact
         :node/weight   1.0
         :node/cycle    0
         :node/tag-refs (mapv #(vector :tag/path %) tag-paths)}])
    uuid))

;; --- Tests ---

(deftest by-tag-test
  (testing "finds nodes with a specific tag"
    (create-tagged-node! *conn* "Clojure macros" ["languages/clojure"])
    (create-tagged-node! *conn* "Python decorators" ["languages/python"])
    (let [results (query/by-tag (d/db *conn*) "languages/clojure")]
      (is (= 1 (count results)))
      (is (= "Clojure macros" (:node/content (first results)))))))

(deftest by-tags-intersection-test
  (testing "returns only nodes matching ALL tags"
    (create-tagged-node! *conn* "Clojure error handling"
                         ["languages/clojure" "patterns/error-handling"])
    (create-tagged-node! *conn* "Clojure basics"
                         ["languages/clojure"])
    (create-tagged-node! *conn* "Python error handling"
                         ["languages/python" "patterns/error-handling"])
    (let [results (query/by-tags (d/db *conn*)
                                 {:tags ["languages/clojure"
                                         "patterns/error-handling"]})]
      (is (= 1 (count results)))
      (is (= "Clojure error handling" (:node/content (first results)))))))

(deftest by-any-tags-union-test
  (testing "returns nodes matching ANY of the tags"
    (create-tagged-node! *conn* "Clojure facts" ["languages/clojure"])
    (create-tagged-node! *conn* "Python facts" ["languages/python"])
    (create-tagged-node! *conn* "Unrelated" ["preferences/tooling"])
    (let [results (query/by-any-tags (d/db *conn*)
                                     {:tags ["languages/clojure"
                                             "languages/python"]})]
      (is (= 2 (count results))))))

(deftest by-subtree-test
  (testing "returns nodes with any tag under the subtree"
    (create-tagged-node! *conn* "Clojure" ["languages/clojure"])
    (create-tagged-node! *conn* "Python" ["languages/python"])
    (create-tagged-node! *conn* "Error handling" ["patterns/error-handling"])
    (let [results (query/by-subtree (d/db *conn*) "languages")]
      (is (= 2 (count results)))
      (is (= #{"Clojure" "Python"}
             (set (map :node/content results)))))))

(deftest browse-with-counts-test
  (testing "browse returns tags with node counts"
    (create-tagged-node! *conn* "Fact 1" ["languages/clojure"])
    (create-tagged-node! *conn* "Fact 2" ["languages/clojure"])
    (create-tagged-node! *conn* "Fact 3" ["languages/python"])
    (let [results (query/browse (d/db *conn*) "languages")]
      (is (= 2 (count results)))
      (let [clj-tag (first (filter #(= "clojure" (:tag/name %)) results))
            py-tag  (first (filter #(= "python" (:tag/name %)) results))]
        (is (= 2 (:node-count clj-tag)))
        (is (= 1 (:node-count py-tag)))))))

(deftest browse-root-test
  (testing "browse nil returns root categories"
    (let [results (query/browse (d/db *conn*) nil)]
      (is (= 4 (count results))))))

(deftest node-tag-paths-test
  (testing "returns all tag paths for a node"
    (let [uuid (create-tagged-node! *conn* "Multi-tagged"
                                    ["languages/clojure" "patterns/error-handling"])]
      (is (= #{"languages/clojure" "patterns/error-handling"}
             (set (query/node-tag-paths (d/db *conn*) uuid)))))))

(deftest empty-queries-test
  (testing "queries with no matching data return empty"
    (is (empty? (query/by-tag (d/db *conn*) "languages/haskell")))
    (is (empty? (query/by-tags (d/db *conn*) {:tags ["nonexistent"]})))
    (is (nil? (query/by-tags (d/db *conn*) {:tags []})))))
