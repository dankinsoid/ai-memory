(ns ai-memory.util.date-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai-memory.util.date :as date])
  (:import [java.time Instant LocalDate ZoneOffset Duration]
           [java.util Date]))

(deftest nil-returns-nil
  (is (nil? (date/parse-date-param nil))))

(deftest today-returns-start-of-today
  (let [result   (date/parse-date-param "today")
        expected (Date/from (.toInstant (.atStartOfDay (LocalDate/now ZoneOffset/UTC) ZoneOffset/UTC)))]
    (is (instance? Date result))
    (is (= expected result))))

(deftest yesterday-returns-start-of-yesterday
  (let [result   (date/parse-date-param "yesterday")
        expected (Date/from (.toInstant (.atStartOfDay (.minusDays (LocalDate/now ZoneOffset/UTC) 1) ZoneOffset/UTC)))]
    (is (= expected result))))

(deftest relative-days
  (testing "7d parses to approximately 7 days ago"
    (let [result (date/parse-date-param "7d")
          now    (Instant/now)
          diff   (Math/abs (.toMillis (Duration/between (.toInstant result) (.minus now (Duration/ofDays 7)))))]
      (is (instance? Date result))
      (is (< diff 1000) "Should be within 1 second of 7 days ago"))))

(deftest relative-weeks
  (testing "2w parses to approximately 14 days ago"
    (let [result (date/parse-date-param "2w")
          now    (Instant/now)
          diff   (Math/abs (.toMillis (Duration/between (.toInstant result) (.minus now (Duration/ofDays 14)))))]
      (is (< diff 1000)))))

(deftest relative-months
  (testing "1m parses to start of day 1 month ago"
    (let [result   (date/parse-date-param "1m")
          expected (.minusMonths (LocalDate/now ZoneOffset/UTC) 1)
          expected-date (Date/from (.toInstant (.atStartOfDay expected ZoneOffset/UTC)))]
      (is (= expected-date result)))))

(deftest iso-date
  (testing "2026-02-01 parses to start of that day UTC"
    (let [result (date/parse-date-param "2026-02-01")]
      (is (= (Date/from (Instant/parse "2026-02-01T00:00:00Z")) result)))))

(deftest iso-datetime
  (testing "full ISO datetime parses exactly"
    (let [result (date/parse-date-param "2026-02-01T15:30:00Z")]
      (is (= (Date/from (Instant/parse "2026-02-01T15:30:00Z")) result)))))

(deftest invalid-throws
  (testing "invalid string throws IllegalArgumentException"
    (is (thrown? IllegalArgumentException (date/parse-date-param "not-a-date")))
    (is (thrown? IllegalArgumentException (date/parse-date-param "abc")))))
