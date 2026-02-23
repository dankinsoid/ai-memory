(ns ai-memory.tag.query-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [ai-memory.db.core :as db]
            [ai-memory.tag.core :as tag]
            [ai-memory.tag.query :as query]
            [ai-memory.decay.core :as decay]))

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
  "Creates a node with tag refs and increments materialized counts.
   Optional opts map with :updated-at, :weight, :cycle.
   Returns Datomic entity ID."
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
                      :node/weight     (or (:weight opts) 1.0)
                      :node/cycle      (or (:cycle opts) 0)
                      :node/tag-refs   tag-refs
                      :node/created-at now
                      :node/updated-at (or (:updated-at opts) now)}
           tx @(d/transact conn (into [node-map] count-txs))]
       (d/resolve-tempid (:db-after tx) (:tempids tx) tempid)))))

(defn- set-tick! [conn tick-val]
  @(d/transact conn [{:db/id :tick/singleton :tick/value tick-val}]))

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

(def aspect-tag-count (count db/aspect-tags))

(deftest browse-with-counts-test
  (testing "browse returns tags with materialized node counts sorted by count desc"
    (create-tagged-node! *conn* "Fact 1" ["clj"])
    (create-tagged-node! *conn* "Fact 2" ["clj"])
    (create-tagged-node! *conn* "Fact 3" ["python"])
    (let [results (query/browse (d/db *conn*) {})
          with-nodes (filterv #(pos? (or (:tag/node-count %) 0)) results)]
      ;; Total includes aspect tags, but only 2 have nodes
      (is (= (+ 2 aspect-tag-count) (count results)))
      (is (= 2 (count with-nodes)))
      (let [first-tag (first with-nodes)]
        (is (= "clj" (:tag/name first-tag)))
        (is (= 2 (:tag/node-count first-tag))))
      (let [second-tag (second with-nodes)]
        (is (= "python" (:tag/name second-tag)))
        (is (= 1 (:tag/node-count second-tag)))))))

(deftest browse-limit-offset-test
  (testing "browse respects limit and offset"
    (create-tagged-node! *conn* "A" ["clj"])
    (create-tagged-node! *conn* "A" ["clj"])
    (create-tagged-node! *conn* "B" ["python"])
    (create-tagged-node! *conn* "C" ["rust"])
    (let [total   (+ 3 aspect-tag-count)
          page1   (query/browse (d/db *conn*) {:limit 2 :offset 0})
          page2   (query/browse (d/db *conn*) {:limit 2 :offset 2})
          page-last (query/browse (d/db *conn*) {:limit 50 :offset (- total 1)})]
      (is (= 2 (count page1)))
      (is (= 2 (count page2)))
      (is (= 1 (count page-last))))))

(deftest browse-only-aspect-tags-test
  (testing "browse returns seeded aspect tags when no user tags created"
    (is (= aspect-tag-count (count (query/browse (d/db *conn*) {}))))))

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

;; --- Date-filtered query tests ---

(def ^:private day-ms (* 24 60 60 1000))

(defn- days-ago [n]
  (java.util.Date. (- (System/currentTimeMillis) (* n day-ms))))

(deftest by-tags-with-since-test
  (testing "by-tags filters by :since on updated-at"
    (create-tagged-node! *conn* "Old fact" ["clj"] {:updated-at (days-ago 30)})
    (create-tagged-node! *conn* "New fact" ["clj"] {:updated-at (days-ago 1)})
    (let [results (query/by-tags (d/db *conn*) {:tags ["clj"] :since (days-ago 7)})]
      (is (= 1 (count results)))
      (is (= "New fact" (:node/content (first results)))))))

(deftest by-tags-with-since-until-test
  (testing "by-tags filters by date range"
    (create-tagged-node! *conn* "Old" ["clj"] {:updated-at (days-ago 30)})
    (create-tagged-node! *conn* "Mid" ["clj"] {:updated-at (days-ago 10)})
    (create-tagged-node! *conn* "New" ["clj"] {:updated-at (days-ago 1)})
    (let [results (query/by-tags (d/db *conn*)
                    {:tags ["clj"] :since (days-ago 15) :until (days-ago 5)})]
      (is (= 1 (count results)))
      (is (= "Mid" (:node/content (first results)))))))

(deftest by-date-range-test
  (testing "by-date-range returns nodes in date range without tag filter"
    (create-tagged-node! *conn* "Old" ["clj"] {:updated-at (days-ago 30)})
    (create-tagged-node! *conn* "Recent" ["python"] {:updated-at (days-ago 2)})
    (let [results (query/by-date-range (d/db *conn*) {:since (days-ago 7)})]
      (is (= 1 (count results)))
      (is (= "Recent" (:node/content (first results)))))))

(deftest fetch-by-tag-sets-with-dates-test
  (testing "fetch-by-tag-sets respects date params"
    (create-tagged-node! *conn* "Old clj" ["clj"] {:updated-at (days-ago 30)})
    (create-tagged-node! *conn* "New clj" ["clj"] {:updated-at (days-ago 1)})
    (let [results (query/fetch-by-tag-sets (d/db *conn*) nil
                    [["clj"]]
                    {:limit 50 :since (days-ago 7)})]
      (is (= 1 (count (:facts (first results)))))
      (is (= "New clj" (:node/content (first (:facts (first results)))))))))

(deftest fetch-no-tags-date-only-test
  (testing "fetch-by-tag-sets with no tags returns date-filtered facts"
    (create-tagged-node! *conn* "Old" ["clj"] {:updated-at (days-ago 30)})
    (create-tagged-node! *conn* "Recent" ["python"] {:updated-at (days-ago 2)})
    (let [results (query/fetch-by-tag-sets (d/db *conn*) nil
                    nil
                    {:limit 50 :since (days-ago 7)})]
      (is (= 1 (count results)))
      (is (= [] (:tags (first results))))
      (is (= 1 (count (:facts (first results))))))))

(deftest fetch-latest-n-test
  (testing "fetch-by-tag-sets with date sort returns latest N sorted by date desc"
    (create-tagged-node! *conn* "Oldest" ["clj"] {:updated-at (days-ago 30)})
    (create-tagged-node! *conn* "Middle" ["python"] {:updated-at (days-ago 10)})
    (create-tagged-node! *conn* "Newest" ["rust"] {:updated-at (days-ago 1)})
    (let [results (query/fetch-by-tag-sets (d/db *conn*) nil nil {:limit 2 :sort-by "date"})
          facts   (:facts (first results))]
      (is (= 2 (count facts)))
      (is (= "Newest" (:node/content (first facts))))
      (is (= "Middle" (:node/content (second facts)))))))

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

;; --- Sort tests ---

(deftest sort-by-weight-test
  (testing "weight sort puts reinforced old fact above newer unreinforced fact"
    ;; Simulate: tick is at 100
    (set-tick! *conn* 100)
    ;; Old reinforced fact: weight=3.0, cycle=98 (recently reinforced)
    ;; effective = 3.0 * 0.95^(100-98) = 3.0 * 0.9025 = 2.7075
    (create-tagged-node! *conn* "reinforced-old" ["clj"]
                         {:weight 3.0 :cycle 98 :updated-at (days-ago 20)})
    ;; New unreinforced fact: weight=1.0, cycle=99
    ;; effective = 1.0 * 0.95^(100-99) = 1.0 * 0.95 = 0.95
    (create-tagged-node! *conn* "new-unreinforced" ["clj"]
                         {:weight 1.0 :cycle 99 :updated-at (days-ago 1)})
    (let [results (query/fetch-by-tag-sets (d/db *conn*) nil
                    [["clj"]]
                    {:limit 50 :sort-by "weight"})
          facts (:facts (first results))]
      (is (= "reinforced-old" (:node/content (first facts))))
      (is (= "new-unreinforced" (:node/content (second facts)))))))

(deftest sort-by-date-explicit-test
  (testing "date sort puts newer fact first regardless of weight"
    (set-tick! *conn* 100)
    ;; High weight but old date
    (create-tagged-node! *conn* "heavy-old" ["clj"]
                         {:weight 5.0 :cycle 98 :updated-at (days-ago 20)})
    ;; Low weight but recent date
    (create-tagged-node! *conn* "light-new" ["clj"]
                         {:weight 1.0 :cycle 99 :updated-at (days-ago 1)})
    (let [results (query/fetch-by-tag-sets (d/db *conn*) nil
                    [["clj"]]
                    {:limit 50 :sort-by "date"})
          facts (:facts (first results))]
      (is (= "light-new" (:node/content (first facts))))
      (is (= "heavy-old" (:node/content (second facts)))))))

(deftest sort-by-weight-default-test
  (testing "default sort (no sort-by param) uses weight"
    (set-tick! *conn* 50)
    ;; Abandoned fact: weight=1.0, cycle=0 → effective = 1.0 * 0.95^50 ≈ 0.077
    (create-tagged-node! *conn* "abandoned" ["clj"]
                         {:weight 1.0 :cycle 0 :updated-at (days-ago 1)})
    ;; Active fact: weight=2.0, cycle=49 → effective = 2.0 * 0.95^1 = 1.9
    (create-tagged-node! *conn* "active" ["clj"]
                         {:weight 2.0 :cycle 49 :updated-at (days-ago 10)})
    (let [results (query/fetch-by-tag-sets (d/db *conn*) nil
                    [["clj"]]
                    {:limit 50})
          facts (:facts (first results))]
      (is (= "active" (:node/content (first facts))))
      (is (= "abandoned" (:node/content (second facts)))))))
