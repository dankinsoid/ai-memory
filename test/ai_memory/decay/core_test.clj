(ns ai-memory.decay.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai-memory.decay.core :as decay]))

(deftest effective-weight-test
  (testing "no elapsed cycles — weight unchanged"
    (is (= 1.0 (decay/effective-weight 1.0 5 5 0.95))))

  (testing "weight decays exponentially"
    (let [w (decay/effective-weight 1.0 0 10 0.95)]
      (is (< w 1.0))
      (is (> w 0.5))))

  (testing "zero base weight stays zero"
    (is (= 0.0 (decay/effective-weight 0.0 0 100 0.95)))))

(deftest should-archive-test
  (testing "high weight, recent cycle — not archived"
    (is (false? (decay/should-archive? 1.0 99 100 0.95 0.01))))

  (testing "low weight after many cycles — archived"
    (is (true? (decay/should-archive? 0.5 0 200 0.95 0.01)))))
