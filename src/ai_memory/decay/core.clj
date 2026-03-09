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

(defn apply-score
  "Reinforcement formula: asymptotic approach to 1.0 for positive score, linear decrease for negative.
   current — current weight ∈ [0.0, 1.0)
   score   — reinforcement signal (positive = strengthen, negative = weaken)
   factor  — learning rate / association strength
   Result never reaches 1.0 via regular reinforce — 1.0 is reserved for eternal facts."
  [current score factor]
  (if (pos? score)
    (+ current (* score factor (- 1.0 current)))
    (max 0.0 (+ current (* score factor)))))

(defn should-archive?
  "Returns true if effective weight is below archival threshold."
  [base last-cycle current-cycle decay-k threshold]
  (< (effective-weight base last-cycle current-cycle decay-k)
     threshold))
