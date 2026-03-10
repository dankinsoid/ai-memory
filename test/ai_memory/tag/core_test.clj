(ns ai-memory.tag.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [ai-memory.db.core :as db]
            [ai-memory.store.protocols :as p]
            [ai-memory.store.datomic-store :as datomic-store]
            [ai-memory.store.memory-vector-store :as mem-vectors]
            [ai-memory.store.random-embedding :as rand-emb]
            [ai-memory.service.tags :as tags]
            [ai-memory.tag.core :as tag]))

(def ^:dynamic *stores* nil)

(defn- test-uri []
  (str "datomic:mem://tag-test-" (d/squuid)))

(defn with-stores [f]
  (let [uri  (test-uri)
        conn (db/connect uri)
        _    (db/ensure-schema conn)
        emb  (rand-emb/create)
        tvs  (mem-vectors/create "test-tags")
        _    (p/ensure-store! tvs (p/embedding-dim emb))]
    (binding [*stores* {:fact-store       (datomic-store/create conn)
                        :tag-vector-store tvs
                        :embedding        emb
                        :conn             conn}]
      (try
        (f)
        (finally
          (d/delete-database uri))))))

(use-fixtures :each with-stores)

;; --- Tests ---

(deftest ensure-vectorizes-new-tags-test
  (testing "tags/ensure! embeds new tags into the tag vector store"
    (tags/ensure! *stores* ["clojure" "python"])
    (let [tvs (:tag-vector-store *stores*)
          emb (:embedding *stores*)
          ;; search for "clojure" — should find the tag we just embedded
          qvec    (p/embed-query emb "clojure")
          results (p/search tvs qvec 10 {})]
      (is (= 2 (count results)))
      ;; verify payloads contain correct tag names
      (let [tag-names (set (map #(get-in % [:payload :tag_name]) results))]
        (is (contains? tag-names "clojure"))
        (is (contains? tag-names "python"))))))

(deftest ensure-idempotent-no-duplicate-vectors-test
  (testing "calling ensure! twice does not duplicate vectors"
    (tags/ensure! *stores* ["clojure"])
    (tags/ensure! *stores* ["clojure"])
    (let [tvs  (:tag-vector-store *stores*)
          info (p/store-info tvs)]
      ;; only 1 vector point, not 2
      (is (= 1 (:points-count info))))))

(deftest ensure-uses-deterministic-point-id-test
  (testing "tag vector point ID is deterministic (tag/tag-point-id)"
    (tags/ensure! *stores* ["rust"])
    (let [tvs     (:tag-vector-store *stores*)
          emb     (:embedding *stores*)
          qvec    (p/embed-query emb "rust")
          results (p/search tvs qvec 1 {})
          hit     (first results)]
      (is (= (tag/tag-point-id "rust") (str (:id hit)))))))
