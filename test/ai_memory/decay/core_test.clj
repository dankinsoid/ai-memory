(ns ai-memory.decay.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai-memory.decay.core :as decay]))

(deftest effective-weight-test
  (testing "no elapsed cycles — weight is 1.0 regardless of base"
    (is (= 1.0 (decay/effective-weight 0.0 5 5 5)))
    (is (= 1.0 (decay/effective-weight 0.5 5 5 5))))

  (testing "weight decays over time for base < 1.0"
    (let [w (decay/effective-weight 0.0 0 10 5)]
      (is (< w 1.0))
      (is (> w 0.0))))

  (testing "higher base decays slower"
    (let [w-low  (decay/effective-weight 0.0 0 50 5)
          w-high (decay/effective-weight 0.8 0 50 5)]
      (is (> w-high w-low))))

  (testing "base=1.0 is eternal — never decays"
    (is (= 1.0 (decay/effective-weight 1.0 0 1000 5)))
    (is (= 1.0 (decay/effective-weight 1.0 0 0 5))))

  (testing "base > 1.0 also treated as eternal"
    (is (= 1.0 (decay/effective-weight 2.0 0 100 5))))

  (testing "base=0.0, k=5: half-life ≈ 31 ticks"
    ;; (elapsed+1)^(-1/5) = 0.5 when elapsed+1 = 2^5 = 32, i.e. elapsed=31
    (let [w (decay/effective-weight 0.0 0 31 5)]
      (is (< (Math/abs (- w 0.5)) 0.01))))

  (testing "zero elapsed — always 1.0"
    (is (= 1.0 (decay/effective-weight 0.0 100 100 5)))))

(deftest should-archive-test
  (testing "high base, recent cycle — not archived"
    (is (false? (decay/should-archive? 0.9 99 100 5 0.5))))

  (testing "base=0 after many cycles — archived at threshold 0.2"
    ;; (10001)^(-0.2) ≈ 0.158 < 0.2
    (is (true? (decay/should-archive? 0.0 0 10000 5 0.2))))

  (testing "eternal fact never archived"
    (is (false? (decay/should-archive? 1.0 0 10000 5 0.99)))))
