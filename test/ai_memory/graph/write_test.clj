(ns ai-memory.graph.write-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [ai-memory.db.core :as db]
            [ai-memory.graph.edge :as edge]
            [ai-memory.graph.node :as node]
            [ai-memory.graph.write :as write]))

;; --- In-memory Datomic fixture (no TEI/Qdrant) ---

(def ^:dynamic *conn* nil)

(defn- test-uri []
  (str "datomic:mem://write-test-" (d/squuid)))

(defn with-datomic [f]
  (let [uri  (test-uri)
        conn (db/connect uri)]
    (db/ensure-schema conn)
    (write/reset-contexts!)
    (binding [*conn* conn]
      (try
        (f)
        (finally
          (write/reset-contexts!)
          (d/delete-database uri))))))

(use-fixtures :each with-datomic)

;; --- Helpers ---

(defn- create-test-node!
  "Creates a node directly (bypassing write pipeline) for test setup."
  ([conn content] (create-test-node! conn content 0))
  ([conn content tick]
   (let [uuid (d/squuid)]
     @(d/transact conn
        [{:db/id        (d/tempid :db.part/user)
          :node/id      uuid
          :node/content content
          :node/type    :node.type/fact
          :node/weight  1.0
          :node/cycle   tick}])
     uuid)))

(defn- all-edges [conn]
  (edge/find-all (d/db conn)))

(defn- edges-between [conn from-id to-id]
  (edge/find-edge-between (d/db conn) from-id to-id))

;; --- Tests ---

(deftest batch-edges-test
  (testing "batch of 3 nodes creates bidirectional edges (3 pairs = 6 edges)"
    (let [a (create-test-node! *conn* "Clojure is a Lisp")
          b (create-test-node! *conn* "Clojure runs on JVM")
          c (create-test-node! *conn* "Clojure has immutable data")]
      (#'write/create-batch-edges *conn* [a b c] 1)
      (let [edges (all-edges *conn*)]
        (is (= 6 (count edges)))
        (is (some? (edges-between *conn* a b)))
        (is (some? (edges-between *conn* b a)))
        (is (some? (edges-between *conn* a c)))
        (is (some? (edges-between *conn* c a)))
        (is (some? (edges-between *conn* b c)))
        (is (some? (edges-between *conn* c b)))))))

(deftest batch-edges-single-node-test
  (testing "single node creates no edges"
    (let [a (create-test-node! *conn* "Solo node")]
      (#'write/create-batch-edges *conn* [a] 1)
      (is (= 0 (count (all-edges *conn*)))))))

(deftest context-edges-test
  (testing "context edges are unidirectional new→old with decaying weight"
    (let [a (create-test-node! *conn* "First fact")
          b (create-test-node! *conn* "Second fact")
          c (create-test-node! *conn* "Third fact")
          entries [[a] [b]]
          opts {:association-factor 0.7 :min-association-weight 0.05}
          cnt (#'write/create-context-edges *conn* entries 2 [c] 3 opts)]
      (is (= 2 cnt))
      (is (some? (edges-between *conn* c b)))
      (is (some? (edges-between *conn* c a)))
      (is (nil? (edges-between *conn* a c)))
      (is (nil? (edges-between *conn* b c))))))

(deftest context-edges-weight-decay-test
  (testing "edge weight decreases with Δseq"
    (let [a    (create-test-node! *conn* "Old fact")
          c    (create-test-node! *conn* "New fact")
          entries [[a] [] [] [] []]
          opts {:association-factor 0.7 :min-association-weight 0.05}]
      (#'write/create-context-edges *conn* entries 5 [c] 6 opts)
      (let [db       (d/db *conn*)
            edge-id  (edge/find-edge-between db c a)
            edge-ent (d/pull db [:edge/weight] [:edge/id edge-id])]
        ;; 0.7^5 ≈ 0.168
        (is (< (:edge/weight edge-ent) 0.2))
        (is (> (:edge/weight edge-ent) 0.1))))))

(deftest context-edges-below-threshold-test
  (testing "no edge created when weight < min-association-weight"
    (let [a (create-test-node! *conn* "Ancient fact")
          c (create-test-node! *conn* "Recent fact")
          entries (vec (cons [a] (repeat 19 [])))
          opts {:association-factor 0.7 :min-association-weight 0.05}]
      (#'write/create-context-edges *conn* entries 20 [c] 21 opts)
      (is (= 0 (count (all-edges *conn*)))))))

(deftest different-contexts-no-edges-test
  (testing "nodes from different contexts are not linked via context edges"
    (let [_a (create-test-node! *conn* "Context A fact")
          b  (create-test-node! *conn* "Context B fact")
          opts {:association-factor 0.7 :min-association-weight 0.05}]
      (#'write/create-context-edges *conn* [] 0 [b] 1 opts)
      (is (= 0 (count (all-edges *conn*)))))))

(deftest edge-dedup-strengthens-test
  (testing "find-or-create-edge strengthens existing edge instead of duplicating"
    (let [a (create-test-node! *conn* "Node A")
          b (create-test-node! *conn* "Node B")]
      (edge/find-or-create-edge *conn* a b 0.5 1)
      (edge/find-or-create-edge *conn* a b 0.3 2)
      (let [edges (all-edges *conn*)]
        (is (= 1 (count edges)))
        (is (== 0.8 (:edge/weight (first edges))))))))

(deftest context-cache-integration-test
  (testing "remember calls accumulate context in RAM and create cross-request edges"
    (let [a (create-test-node! *conn* "Fact A")
          b (create-test-node! *conn* "Fact B")]
      (#'write/update-context! "test-ctx" [a] 7200)
      (let [ctx       (#'write/get-context "test-ctx")
            entries   (:entries ctx)
            opts      {:association-factor 0.7 :min-association-weight 0.05}
            cnt       (#'write/create-context-edges *conn* entries 1 [b] 2 opts)]
        (is (= 1 cnt))
        (is (some? (edges-between *conn* b a)))
        (is (nil? (edges-between *conn* a b)))))))

(deftest tick-increments-test
  (testing "global tick increments on each call"
    (is (= 0 (db/current-tick (d/db *conn*))))
    (let [t1 (db/increment-tick! *conn*)
          t2 (db/increment-tick! *conn*)
          t3 (db/increment-tick! *conn*)]
      (is (= 1 t1))
      (is (= 2 t2))
      (is (= 3 t3))
      (is (= 3 (db/current-tick (d/db *conn*)))))))

;; --- Global context tests ---

(deftest global-edges-link-to-recent-nodes-test
  (testing ":global creates unidirectional edges to recent nodes from DB"
    (let [a    (create-test-node! *conn* "Old fact" 8)
          b    (create-test-node! *conn* "Another old fact" 9)
          c    (create-test-node! *conn* "New fact")
          tick 10
          opts {:association-factor 0.7 :min-association-weight 0.05}
          cnt  (#'write/create-global-edges *conn* tick [c] opts)]
      ;; c→a (Δ=2, w=0.49) and c→b (Δ=1, w=0.7)
      (is (= 2 cnt))
      (is (some? (edges-between *conn* c a)))
      (is (some? (edges-between *conn* c b)))
      ;; unidirectional — no reverse edges
      (is (nil? (edges-between *conn* a c)))
      (is (nil? (edges-between *conn* b c))))))

(deftest global-edges-weight-decay-test
  (testing ":global edge weight decays with tick distance"
    (let [a    (create-test-node! *conn* "Distant fact" 5)
          b    (create-test-node! *conn* "Close fact" 9)
          c    (create-test-node! *conn* "New fact")
          tick 10
          opts {:association-factor 0.7 :min-association-weight 0.05}]
      (#'write/create-global-edges *conn* tick [c] opts)
      (let [db      (d/db *conn*)
            edge-ca (d/pull db [:edge/weight] [:edge/id (edges-between *conn* c a)])
            edge-cb (d/pull db [:edge/weight] [:edge/id (edges-between *conn* c b)])]
        ;; c→b: Δ=1, w=0.7
        (is (> (:edge/weight edge-cb) 0.69))
        (is (< (:edge/weight edge-cb) 0.71))
        ;; c→a: Δ=5, w=0.7^5≈0.168
        (is (> (:edge/weight edge-ca) 0.15))
        (is (< (:edge/weight edge-ca) 0.18))))))

(deftest global-edges-skip-old-nodes-test
  (testing ":global skips nodes beyond max-delta threshold"
    (let [;; max-delta for factor=0.7, min=0.05 is floor(log(0.05)/log(0.7)) = 8
          old  (create-test-node! *conn* "Very old fact" 0)
          c    (create-test-node! *conn* "New fact")
          tick 10
          opts {:association-factor 0.7 :min-association-weight 0.05}
          cnt  (#'write/create-global-edges *conn* tick [c] opts)]
      ;; Δ=10, 0.7^10 ≈ 0.028 < 0.05 → no edge
      (is (= 0 cnt))
      (is (nil? (edges-between *conn* c old))))))

(deftest global-edges-exclude-self-test
  (testing ":global does not create self-edges"
    (let [a    (create-test-node! *conn* "Existing fact" 9)
          c    (create-test-node! *conn* "New fact" 10)
          tick 10
          opts {:association-factor 0.7 :min-association-weight 0.05}]
      ;; c is in node-ids AND in recent (cycle=10 >= min-tick)
      (#'write/create-global-edges *conn* tick [c] opts)
      ;; should only have c→a, not c→c
      (is (some? (edges-between *conn* c a)))
      (is (nil? (edges-between *conn* c c))))))

;; --- Entity node tests ---

(defn- create-entity-node!
  "Creates an entity node directly for test setup."
  [conn content tick]
  (let [uuid (d/squuid)]
    @(d/transact conn
       [{:db/id        (d/tempid :db.part/user)
         :node/id      uuid
         :node/content content
         :node/type    :node.type/entity
         :node/weight  1.0
         :node/cycle   tick}])
    uuid))

(deftest entity-find-by-content-test
  (testing "find-entity-by-content returns entity by exact match"
    (let [uuid (create-entity-node! *conn* "user" 0)
          db   (d/db *conn*)]
      (is (= uuid (:node/id (node/find-entity-by-content db "user"))))
      (is (nil? (node/find-entity-by-content db "User")))
      (is (nil? (node/find-entity-by-content db "user preferences"))))))

(deftest entity-dedup-exact-match-test
  (testing "entity nodes dedup via exact content match, not vector search"
    (let [uuid (create-entity-node! *conn* "Clojure" 1)
          db   (d/db *conn*)
          node-data {:content "Clojure" :node-type :entity}
          opts {:dedup-threshold 0.85 :reinforcement-delta 0.2}
          result (#'write/find-duplicate-node db {} "Clojure" node-data opts)]
      (is (some? result))
      (is (= uuid (:node/id result))))))

(deftest entity-not-found-creates-new-test
  (testing "entity with no match creates new node"
    (let [db        (d/db *conn*)
          node-data {:content "new-entity" :node-type :entity}
          opts      {:dedup-threshold 0.85 :reinforcement-delta 0.2}
          result    (#'write/find-duplicate-node db {} "new-entity" node-data opts)]
      (is (nil? result)))))

(deftest entity-hub-via-batch-edges-test
  (testing "entity node becomes a hub via batch edges across separate writes"
    (let [;; First write: entity "user" + fact, linked by batch edges
          e1 (create-entity-node! *conn* "user" 1)
          f1 (create-test-node! *conn* "user prefers functional style" 1)
          _ (#'write/create-batch-edges *conn* [e1 f1] 1)
          ;; Second write: same entity (reused via dedup) + new fact
          f2 (create-test-node! *conn* "user is 31 years old" 2)
          _ (#'write/create-batch-edges *conn* [e1 f2] 2)]
      ;; e1 is hub: connected to both facts
      (is (some? (edges-between *conn* e1 f1)))
      (is (some? (edges-between *conn* f1 e1)))
      (is (some? (edges-between *conn* e1 f2)))
      (is (some? (edges-between *conn* f2 e1)))
      ;; facts are NOT directly connected
      (is (nil? (edges-between *conn* f1 f2)))
      (is (nil? (edges-between *conn* f2 f1))))))
