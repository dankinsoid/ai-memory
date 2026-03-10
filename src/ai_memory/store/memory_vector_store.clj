;; @ai-generated(solo)
(ns ai-memory.store.memory-vector-store
  "In-memory VectorStore for local dev — no external vector DB dependency.
   Stores vectors in an atom, searches via brute-force cosine similarity."
  (:require [ai-memory.store.protocols :as p]
            [clojure.tools.logging :as log]))

(defn- dot-product
  "Dot product of two vectors (seqs of doubles)."
  ^double [a b]
  (loop [sum 0.0
         xs  (seq a)
         ys  (seq b)]
    (if (and xs ys)
      (recur (+ sum (* (double (first xs)) (double (first ys))))
             (next xs)
             (next ys))
      sum)))

(defn- magnitude
  "Euclidean norm of a vector."
  ^double [v]
  (Math/sqrt (dot-product v v)))

(defn- cosine-similarity
  "Cosine similarity in [-1, 1]. Returns 0.0 if either vector is zero."
  ^double [a b]
  (let [ma (magnitude a)
        mb (magnitude b)]
    (if (or (zero? ma) (zero? mb))
      0.0
      (/ (dot-product a b) (* ma mb)))))

(defrecord InMemoryVectorStore [collection points-atom dim-atom]
  p/VectorStore
  (ensure-store! [_ dim]
    (reset! dim-atom dim)
    (log/info "In-memory vector store ready:" collection "dim=" dim))

  (upsert! [_ id vector payload]
    (swap! points-atom assoc id {:id id :vector vector :payload payload}))

  (search [_ query-vector top-k _opts]
    (->> (vals @points-atom)
         (map (fn [{:keys [id vector payload]}]
                {:id id
                 :score (cosine-similarity query-vector vector)
                 :payload payload}))
         (sort-by :score >)
         (take top-k)
         vec))

  (delete! [_ id]
    (swap! points-atom dissoc id))

  (delete-all! [_]
    (reset! points-atom {})
    (log/info "In-memory vector store cleared:" collection))

  (store-info [_]
    {:reachable?   true
     :status       "in-memory"
     :vector-count (count @points-atom)
     :points-count (count @points-atom)}))

(defn create
  "Creates an in-memory VectorStore.
   `collection` — logical collection name (e.g. \"nodes\", \"tags\")."
  [collection]
  (->InMemoryVectorStore collection (atom {}) (atom nil)))
