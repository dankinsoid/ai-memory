(ns ai-memory.decay.core)

(def default-decay-factor
  "Per-tick decay factor. 0.95^10 ≈ 0.60, 0.95^50 ≈ 0.08."
  0.95)

(defn effective-weight
  "Computes decayed weight: base * decay_factor ^ (current_cycle - last_cycle)"
  [base-weight last-cycle current-cycle decay-factor]
  (let [elapsed (- current-cycle last-cycle)]
    (if (pos? elapsed)
      (* base-weight (Math/pow decay-factor elapsed))
      base-weight)))

(defn should-archive?
  "Returns true if effective weight is below archival threshold."
  [base-weight last-cycle current-cycle decay-factor threshold]
  (< (effective-weight base-weight last-cycle current-cycle decay-factor)
     threshold))
