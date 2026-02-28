(ns ai-memory.decay.core)

(def default-decay-k
  "Power-law decay divisor. Controls how fast facts decay.
   half-life (ticks) = 2^(k/(1-base)) - 1.
   At k=5, base=0: half-life ≈ 31 ticks."
  5)

(defn effective-weight
  "Power-law decay: (elapsed+1)^((base-1)/k).
   base ∈ [0.0, 1.0]. At base=1.0 (eternal) always returns 1.0.
   At elapsed=0 always returns 1.0 regardless of base."
  [base last-cycle current-cycle decay-k]
  (if (>= base 1.0)
    1.0
    (let [elapsed (max 0 (- current-cycle last-cycle))]
      (Math/pow (inc elapsed) (/ (dec base) (double decay-k))))))

(defn should-archive?
  "Returns true if effective weight is below archival threshold."
  [base last-cycle current-cycle decay-k threshold]
  (< (effective-weight base last-cycle current-cycle decay-k)
     threshold))
