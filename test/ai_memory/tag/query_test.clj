(ns ai-memory.tag.query-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [datalevin.core :as d]
            [ai-memory.db.core :as db]
            [ai-memory.tag.core :as tag]
            [ai-memory.tag.query :as query]
            [ai-memory.decay.core :as decay]))

(def ^:dynamic *conn* nil)

(defn- test-db-path []
  (str (System/getProperty "java.io.tmpdir") "/ai-memory-query-test-" (random-uuid)))

(defn- delete-dir! [path]
  (let [dir (io/file path)]
    (when (.exists dir)
      (doseq [f (reverse (sort (file-seq dir)))]
        (.delete f)))))

(defn with-datalevin [f]
  (let [path (test-db-path)
        conn (db/connect path)]
    (db/ensure-schema conn)
    (binding [*conn* conn]
      (try
        (f)
        (finally
          (d/close conn)
          (delete-dir! path))))))

(use-fixtures :each with-datalevin)

;; --- Helpers ---

(defn- inc-tag-counts! [conn tag-names]
  (let [db (d/db conn)]
    (doseq [tag-name tag-names]
      (let [current (or (:tag/node-count (d/pull db [:tag/node-count] [:tag/name tag-name])) 0)]
        (d/transact! conn [[:db/add [:tag/name tag-name] :tag/node-count (inc current)]])))))

(defn- create-tagged-node!
  "Creates a node with tag refs and increments materialized counts.
   Optional opts map with :updated-at, :weight, :cycle.
   Returns entity ID."
  ([conn content tag-names] (create-tagged-node! conn content tag-names nil))
  ([conn content tag-names opts]
   (let [now     (java.util.Date.)
         tempid  "new-node"]
     (doseq [n tag-names]
       (tag/ensure-tag! conn n))
     (let [tag-refs (mapv #(vector :tag/name %) tag-names)
           node-map {:db/id           tempid
                     :node/content    content
                     :node/weight     (or (:weight opts) 0.0)
                     :node/cycle      (or (:cycle opts) 0)
                     :node/tag-refs   tag-refs
                     :node/created-at now
                     :node/updated-at (or (:updated-at opts) now)}
           tx       (d/transact! conn [node-map])
           eid      (get-in tx [:tempids tempid])]
       (inc-tag-counts! conn tag-names)
       eid))))

(defn- set-tick! [conn tick-val]
  (d/transact! conn [{:db/id [:tick/id "singleton"] :tick/value tick-val}]))

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
    (d/transact! *conn* [[:db/add [:tag/name "clj"] :tag/node-count 999]])
    (is (= 999 (:tag/node-count (d/pull (d/db *conn*) [:tag/node-count] [:tag/name "clj"]))))
    ;; Reconcile
    (let [result (query/reconcile-counts! *conn*)]
      (is (pos? (:tags-updated result)))
      (is (= 2 (:tag/node-count (d/pull (d/db *conn*) [:tag/node-count] [:tag/name "clj"])))))))

;; --- Sort tests ---

(deftest sort-by-weight-test
  (testing "weight sort puts reinforced old fact above newer unreinforced fact"
    ;; Simulate: tick is at 100
    (set-tick! *conn* 100)
    ;; Old reinforced fact: base=0.9, cycle=98
    ;; power-law effective = 3^((0.9-1)/5) = 3^(-0.02) ≈ 0.978
    (create-tagged-node! *conn* "reinforced-old" ["clj"]
                         {:weight 0.9 :cycle 98 :updated-at (days-ago 20)})
    ;; New unreinforced fact: base=0.2, cycle=99
    ;; power-law effective = 2^((0.2-1)/5) = 2^(-0.16) ≈ 0.895
    (create-tagged-node! *conn* "new-unreinforced" ["clj"]
                         {:weight 0.2 :cycle 99 :updated-at (days-ago 1)})
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

(deftest sort-by-weight-explicit-test
  (testing "explicit weight sort puts higher-effective-weight fact first"
    (set-tick! *conn* 50)
    ;; Abandoned fact: base=0.0, cycle=0 → effective = 51^(-0.2) ≈ 0.56
    (create-tagged-node! *conn* "abandoned" ["clj"]
                         {:weight 0.0 :cycle 0 :updated-at (days-ago 1)})
    ;; Active fact: base=0.8, cycle=49 → effective = 2^(-0.04) ≈ 0.97
    (create-tagged-node! *conn* "active" ["clj"]
                         {:weight 0.8 :cycle 49 :updated-at (days-ago 10)})
    (let [results (query/fetch-by-tag-sets (d/db *conn*) nil
                    [["clj"]]
                    {:limit 50 :sort-by "weight"})
          facts (:facts (first results))]
      (is (= "active" (:node/content (first facts))))
      (is (= "abandoned" (:node/content (second facts)))))))
