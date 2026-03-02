(ns ai-memory.tag.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [datalevin.core :as d]
            [ai-memory.db.core :as db]
            [ai-memory.tag.core :as tag]))

(def ^:dynamic *conn* nil)

(defn- test-db-path []
  (str (System/getProperty "java.io.tmpdir") "/ai-memory-tag-test-" (random-uuid)))

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

;; --- Tests ---

(deftest ensure-tag-creates-test
  (testing "ensure-tag! creates a new atomic tag"
    (let [name (tag/ensure-tag! *conn* "clj")
          db   (d/db *conn*)
          e    (d/pull db [:tag/name] [:tag/name "clj"])]
      (is (= "clj" name))
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
    (tag/ensure-tag! *conn* "pref")
    (let [tags     (tag/all-tags (d/db *conn*))
          names    (set (map :tag/name tags))]
      (is (= (+ 3 aspect-tag-count) (count tags)))
      (is (every? names ["clj" "python" "pref"])))))

(deftest all-tags-only-aspect-test
  (testing "all-tags returns seeded aspect tags when no user tags created"
    (is (= aspect-tag-count (count (tag/all-tags (d/db *conn*)))))))
