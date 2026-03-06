(ns ai-memory.tag.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [ai-memory.db.core :as db]
            [ai-memory.tag.core :as tag]))

(def ^:dynamic *conn* nil)

(defn- test-uri []
  (str "datomic:mem://tag-test-" (d/squuid)))

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

;; --- Tests ---

(deftest ensure-tag-creates-test
  (testing "ensure-tag! creates a new atomic tag"
    (let [name (tag/ensure-tag! *conn* "clj")
          db   (d/db *conn*)
          e    (d/entity db [:tag/name "clj"])]
      (is (= "clj" name))
      (is (some? e))
      (is (= "clj" (:tag/name e))))))

(def aspect-tag-count (count db/aspect-tags))

(deftest ensure-tag-idempotent-test
  (testing "calling ensure-tag! twice returns same name, no duplicates"
    (let [n1 (tag/ensure-tag! *conn* "clj")
          n2 (tag/ensure-tag! *conn* "clj")]
      (is (= n1 n2))
      (is (= (+ 1 aspect-tag-count)
             (count (tag/all-tags (d/db *conn*))))))))

(deftest all-tags-test
  (testing "all-tags returns all created tags (plus seeded aspect tags)"
    (tag/ensure-tag! *conn* "clj")
    (tag/ensure-tag! *conn* "python")
    (tag/ensure-tag! *conn* "preference")
    (let [tags     (tag/all-tags (d/db *conn*))
          names    (set (map :tag/name tags))]
      (is (= (+ 3 aspect-tag-count) (count tags)))
      (is (every? names ["clj" "python" "preference"])))))

(deftest all-tags-only-aspect-test
  (testing "all-tags returns seeded aspect tags when no user tags created"
    (is (= aspect-tag-count (count (tag/all-tags (d/db *conn*)))))))
