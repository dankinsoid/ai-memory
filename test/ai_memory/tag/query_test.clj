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
  [conn content tag-names]
  (let [uuid (d/squuid)]
    (doseq [n tag-names]
      (tag/ensure-tag! conn n))
    (let [tag-refs  (mapv #(vector :tag/name %) tag-names)
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

;; --- Existing tests ---

(deftest by-tag-test
  (testing "finds nodes with a specific tag"
    (create-tagged-node! *conn* "Clojure macros" ["clj"])
    (create-tagged-node! *conn* "Python decorators" ["python"])
    (let [results (query/by-tag (d/db *conn*) "clj")]
      (is (= 1 (count results)))
      (is (= "Clojure macros" (:node/content (first results)))))))

(deftest by-tags-intersection-test
  (testing "returns only nodes matching ALL tags"
    (create-tagged-node! *conn* "Clojure error handling"
                         ["clj" "error-handling"])
    (create-tagged-node! *conn* "Clojure basics"
                         ["clj"])
    (create-tagged-node! *conn* "Python error handling"
                         ["python" "error-handling"])
    (let [results (query/by-tags (d/db *conn*)
                                 {:tags ["clj" "error-handling"]})]
      (is (= 1 (count results)))
      (is (= "Clojure error handling" (:node/content (first results)))))))

(deftest by-any-tags-union-test
  (testing "returns nodes matching ANY of the tags"
    (create-tagged-node! *conn* "Clojure facts" ["clj"])
    (create-tagged-node! *conn* "Python facts" ["python"])
    (create-tagged-node! *conn* "Unrelated" ["tooling"])
    (let [results (query/by-any-tags (d/db *conn*)
                                     {:tags ["clj" "python"]})]
      (is (= 2 (count results))))))

(deftest browse-with-counts-test
  (testing "browse returns tags with materialized node counts sorted by count desc"
    (create-tagged-node! *conn* "Fact 1" ["clj"])
    (create-tagged-node! *conn* "Fact 2" ["clj"])
    (create-tagged-node! *conn* "Fact 3" ["python"])
    (let [results (query/browse (d/db *conn*) {})]
      (is (= 2 (count results)))
      (let [first-tag (first results)]
        (is (= "clj" (:tag/name first-tag)))
        (is (= 2 (:tag/node-count first-tag))))
      (let [second-tag (second results)]
        (is (= "python" (:tag/name second-tag)))
        (is (= 1 (:tag/node-count second-tag)))))))

(deftest browse-limit-offset-test
  (testing "browse respects limit and offset"
    (create-tagged-node! *conn* "A" ["clj"])
    (create-tagged-node! *conn* "A" ["clj"])
    (create-tagged-node! *conn* "B" ["python"])
    (create-tagged-node! *conn* "C" ["rust"])
    (let [page1 (query/browse (d/db *conn*) {:limit 2 :offset 0})
          page2 (query/browse (d/db *conn*) {:limit 2 :offset 2})]
      (is (= 2 (count page1)))
      (is (= 1 (count page2))))))

(deftest browse-empty-test
  (testing "browse returns empty when no tags"
    (is (empty? (query/browse (d/db *conn*) {})))))

(deftest node-tags-test
  (testing "returns all tag names for a node"
    (let [uuid (create-tagged-node! *conn* "Multi-tagged"
                                    ["clj" "error-handling"])]
      (is (= #{"clj" "error-handling"}
             (set (query/node-tags (d/db *conn*) uuid)))))))

(deftest empty-queries-test
  (testing "queries with no matching data return empty"
    (is (empty? (query/by-tag (d/db *conn*) "haskell")))
    (is (empty? (query/by-tags (d/db *conn*) {:tags ["nonexistent"]})))
    (is (nil? (query/by-tags (d/db *conn*) {:tags []})))))

;; --- Count by tag sets tests ---

(deftest count-by-tag-sets-test
  (testing "returns counts for each tag set intersection"
    (create-tagged-node! *conn* "A" ["clj" "error-handling"])
    (create-tagged-node! *conn* "B" ["clj" "concurrency"])
    (create-tagged-node! *conn* "C" ["clj"])
    (create-tagged-node! *conn* "D" ["python"])
    (let [results (query/count-by-tag-sets (d/db *conn*) nil
                    [["clj" "error-handling"]
                     ["clj"]
                     ["python"]])]
      (is (= 3 (count results)))
      (is (= 1 (:count (nth results 0))))
      (is (= 3 (:count (nth results 1))))
      (is (= 1 (:count (nth results 2)))))))

(deftest count-by-tag-sets-empty-test
  (testing "returns 0 for non-matching tag sets"
    (let [results (query/count-by-tag-sets (d/db *conn*) nil
                    [["nonexistent"]])]
      (is (= 0 (:count (first results)))))))

;; --- Fetch by tag sets tests ---

(deftest fetch-by-tag-sets-test
  (testing "returns facts grouped by tag set"
    (create-tagged-node! *conn* "A" ["clj" "error-handling"])
    (create-tagged-node! *conn* "B" ["clj"])
    (create-tagged-node! *conn* "C" ["python"])
    (let [results (query/fetch-by-tag-sets (d/db *conn*) nil
                    [["clj"] ["python"]]
                    {:limit 50})]
      (is (= 2 (count results)))
      (is (= 2 (count (:facts (first results)))))
      (is (= 1 (count (:facts (second results))))))))

(deftest fetch-by-tag-sets-limit-test
  (testing "limit caps facts per tag set"
    (create-tagged-node! *conn* "A" ["clj"])
    (create-tagged-node! *conn* "B" ["clj"])
    (create-tagged-node! *conn* "C" ["clj"])
    (let [results (query/fetch-by-tag-sets (d/db *conn*) nil
                    [["clj"]]
                    {:limit 2})]
      (is (= 2 (count (:facts (first results))))))))

;; --- Reconciliation tests ---

(deftest reconcile-counts-test
  (testing "fixes drifted materialized counts"
    (create-tagged-node! *conn* "A" ["clj"])
    (create-tagged-node! *conn* "B" ["clj"])
    ;; Corrupt the count manually
    @(d/transact *conn* [[:db/add [:tag/name "clj"] :tag/node-count 999]])
    (is (= 999 (:tag/node-count (d/entity (d/db *conn*) [:tag/name "clj"]))))
    ;; Reconcile
    (let [result (query/reconcile-counts! *conn*)]
      (is (pos? (:tags-updated result)))
      (is (= 2 (:tag/node-count (d/entity (d/db *conn*) [:tag/name "clj"])))))))
