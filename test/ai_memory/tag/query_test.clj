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
  "Creates a node with tag refs and increments materialized counts."
  [conn content tag-paths]
  (let [uuid (d/squuid)]
    (doseq [p tag-paths]
      (tag/ensure-tag! conn p))
    (let [tag-refs  (mapv #(vector :tag/path %) tag-paths)
          count-txs (mapv (fn [ref] [:fn/inc-tag-count (second ref) 1]) tag-refs)]
      @(d/transact conn
         (into [{:db/id         (d/tempid :db.part/user)
                 :node/id       uuid
                 :node/content  content
                 :node/type     :node.type/fact
                 :node/weight   1.0
                 :node/cycle    0
                 :node/tag-refs tag-refs}]
               count-txs)))
    uuid))

;; --- Existing tests ---

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
  (testing "browse returns tags with materialized node counts"
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

;; --- Taxonomy tests ---

(deftest taxonomy-depth-limited-test
  (testing "returns tree limited to max-depth"
    (tag/ensure-tag! *conn* "languages/clojure/core")
    (tag/ensure-tag! *conn* "languages/clojure/macros")
    (tag/ensure-tag! *conn* "languages/python/django")
    (create-tagged-node! *conn* "Fact" ["languages/clojure/core"])
    (let [tree (query/taxonomy (d/db *conn*) nil 1)]
      ;; depth 1: only root categories, no children
      (is (= 4 (count tree)))
      (is (every? #(not (contains? % :children)) tree))
      ;; languages has children => truncated
      (let [langs (first (filter #(= "languages" (:tag/name %)) tree))]
        (is (true? (:truncated langs)))))))

(deftest taxonomy-with-children-test
  (testing "depth 2 shows one level of children"
    (tag/ensure-tag! *conn* "languages/clojure/core")
    (create-tagged-node! *conn* "Fact 1" ["languages/clojure"])
    (create-tagged-node! *conn* "Fact 2" ["languages/clojure/core"])
    (let [tree  (query/taxonomy (d/db *conn*) nil 2)
          langs (first (filter #(= "languages" (:tag/name %)) tree))]
      (is (contains? langs :children))
      (let [clj (first (filter #(= "clojure" (:tag/name %)) (:children langs)))]
        (is (= 1 (:node-count clj)))
        ;; clojure has children (core) but depth exhausted => truncated
        (is (true? (:truncated clj)))))))

(deftest taxonomy-subtree-test
  (testing "taxonomy from a specific path"
    (tag/ensure-tag! *conn* "languages/clojure/core")
    (tag/ensure-tag! *conn* "languages/clojure/macros")
    (create-tagged-node! *conn* "Core fact" ["languages/clojure/core"])
    (let [tree (query/taxonomy (d/db *conn*) "languages/clojure" 1)]
      (is (= 2 (count tree)))
      (is (= #{"core" "macros"} (set (map :tag/name tree))))
      (let [core-tag (first (filter #(= "core" (:tag/name %)) tree))]
        (is (= 1 (:node-count core-tag)))))))

;; --- Count by tag sets tests ---

(deftest count-by-tag-sets-test
  (testing "returns counts for each tag set intersection"
    (create-tagged-node! *conn* "A" ["languages/clojure" "patterns/error-handling"])
    (create-tagged-node! *conn* "B" ["languages/clojure" "patterns/concurrency"])
    (create-tagged-node! *conn* "C" ["languages/clojure"])
    (create-tagged-node! *conn* "D" ["languages/python"])
    (let [results (query/count-by-tag-sets (d/db *conn*) nil
                    [["languages/clojure" "patterns/error-handling"]
                     ["languages/clojure"]
                     ["languages/python"]])]
      (is (= 3 (count results)))
      (is (= 1 (:count (nth results 0))))
      (is (= 3 (:count (nth results 1))))
      (is (= 1 (:count (nth results 2)))))))

(deftest count-by-tag-sets-empty-test
  (testing "returns 0 for non-matching tag sets"
    (let [results (query/count-by-tag-sets (d/db *conn*) nil
                    [["nonexistent/tag"]])]
      (is (= 0 (:count (first results)))))))

;; --- Fetch by tag sets tests ---

(deftest fetch-by-tag-sets-test
  (testing "returns facts grouped by tag set"
    (create-tagged-node! *conn* "A" ["languages/clojure" "patterns/error-handling"])
    (create-tagged-node! *conn* "B" ["languages/clojure"])
    (create-tagged-node! *conn* "C" ["languages/python"])
    (let [results (query/fetch-by-tag-sets (d/db *conn*) nil
                    [["languages/clojure"] ["languages/python"]]
                    {:limit 50})]
      (is (= 2 (count results)))
      (is (= 2 (count (:facts (first results)))))
      (is (= 1 (count (:facts (second results))))))))

(deftest fetch-by-tag-sets-limit-test
  (testing "limit caps facts per tag set"
    (create-tagged-node! *conn* "A" ["languages/clojure"])
    (create-tagged-node! *conn* "B" ["languages/clojure"])
    (create-tagged-node! *conn* "C" ["languages/clojure"])
    (let [results (query/fetch-by-tag-sets (d/db *conn*) nil
                    [["languages/clojure"]]
                    {:limit 2})]
      (is (= 2 (count (:facts (first results))))))))

;; --- Reconciliation tests ---

(deftest reconcile-counts-test
  (testing "fixes drifted materialized counts"
    (create-tagged-node! *conn* "A" ["languages/clojure"])
    (create-tagged-node! *conn* "B" ["languages/clojure"])
    ;; Corrupt the count manually
    @(d/transact *conn* [[:db/add [:tag/path "languages/clojure"] :tag/node-count 999]])
    (is (= 999 (:tag/node-count (d/entity (d/db *conn*) [:tag/path "languages/clojure"]))))
    ;; Reconcile
    (let [result (query/reconcile-counts! *conn*)]
      (is (pos? (:tags-updated result)))
      (is (= 2 (:tag/node-count (d/entity (d/db *conn*) [:tag/path "languages/clojure"])))))))
