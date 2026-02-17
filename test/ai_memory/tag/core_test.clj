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

(deftest seed-tags-loaded-test
  (testing "ensure-schema loads seed root categories"
    (let [roots (tag/root-tags (d/db *conn*))]
      (is (= 4 (count roots)))
      (is (= #{"languages" "patterns" "projects" "preferences"}
             (set (map :tag/path roots)))))))

(deftest create-root-tag-test
  (testing "creating a root tag (no parent)"
    (let [path (tag/create-tag! *conn* {:name "tools"})
          db   (d/db *conn*)
          t    (tag/find-by-path db "tools")]
      (is (= "tools" path))
      (is (= "tools" (:tag/name t)))
      (is (nil? (:tag/parent t))))))

(deftest create-child-tag-test
  (testing "creating a child tag sets parent ref"
    (tag/create-tag! *conn* {:name "clojure" :parent-path "languages"})
    (let [db (d/db *conn*)
          t  (tag/find-by-path db "languages/clojure")]
      (is (some? t))
      (is (= "clojure" (:tag/name t)))
      (is (= "languages" (get-in t [:tag/parent :tag/path]))))))

(deftest ensure-tag-creates-intermediates-test
  (testing "ensure-tag! creates full hierarchy"
    (tag/ensure-tag! *conn* "languages/jvm/clojure")
    (let [db (d/db *conn*)]
      (is (some? (tag/find-by-path db "languages")))
      (is (some? (tag/find-by-path db "languages/jvm")))
      (is (some? (tag/find-by-path db "languages/jvm/clojure"))))))

(deftest ensure-tag-idempotent-test
  (testing "calling ensure-tag! twice returns same path"
    (let [p1 (tag/ensure-tag! *conn* "languages/rust")
          p2 (tag/ensure-tag! *conn* "languages/rust")]
      (is (= p1 p2))
      (is (= 1 (count (tag/find-by-name (d/db *conn*) "rust")))))))

(deftest find-by-name-test
  (testing "find-by-name returns all tags with same leaf name"
    (tag/ensure-tag! *conn* "languages/core")
    (tag/ensure-tag! *conn* "patterns/core")
    (let [matches (tag/find-by-name (d/db *conn*) "core")]
      (is (= 2 (count matches)))
      (is (= #{"languages/core" "patterns/core"}
             (set (map :tag/path matches)))))))

(deftest children-test
  (testing "children returns only direct children"
    (tag/ensure-tag! *conn* "languages/clojure")
    (tag/ensure-tag! *conn* "languages/python")
    (tag/ensure-tag! *conn* "languages/clojure/spec")
    (let [kids (tag/children (d/db *conn*) "languages")]
      (is (= #{"clojure" "python"}
             (set (map :tag/name kids)))))))

(deftest subtree-test
  (testing "subtree returns all descendants"
    (tag/ensure-tag! *conn* "languages/clojure")
    (tag/ensure-tag! *conn* "languages/clojure/spec")
    (tag/ensure-tag! *conn* "languages/python")
    (let [desc (tag/subtree (d/db *conn*) "languages")]
      (is (= #{"languages/clojure" "languages/clojure/spec" "languages/python"}
             (set (map :tag/path desc)))))))

(deftest full-taxonomy-test
  (testing "full-taxonomy returns nested tree"
    (tag/ensure-tag! *conn* "languages/clojure")
    (let [tree (tag/full-taxonomy (d/db *conn*))
          lang (first (filter #(= "languages" (:tag/name %)) tree))]
      (is (some? lang))
      (is (= 1 (count (:children lang))))
      (is (= "clojure" (:tag/name (first (:children lang))))))))
