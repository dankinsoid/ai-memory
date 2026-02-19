(ns ai-memory.util.date
  "Date parameter parsing for MCP commands.
   Converts string representations to java.util.Date (Datomic format)."
  (:import [java.time Instant LocalDate ZoneOffset Duration Period]
           [java.util Date]))

(defn- start-of-today []
  (.toInstant (.atStartOfDay (LocalDate/now ZoneOffset/UTC) ZoneOffset/UTC)))

(defn parse-date-param
  "Parses a date parameter string into java.util.Date.
   Accepts:
     nil                    → nil
     \"today\"              → start of today UTC
     \"yesterday\"          → start of yesterday UTC
     \"7d\" / \"2w\" / \"1m\"  → relative (N days/weeks/months ago)
     \"2026-02-01\"         → ISO date (start of day UTC)
     \"2026-02-01T15:30:00Z\" → ISO datetime"
  [s]
  (when s
    (let [s (str s)]
      (cond
        (= s "today")
        (Date/from (start-of-today))

        (= s "yesterday")
        (Date/from (.minus (start-of-today) (Duration/ofDays 1)))

        (re-matches #"\d+d" s)
        (let [n (parse-long (subs s 0 (dec (count s))))]
          (Date/from (.minus (Instant/now) (Duration/ofDays n))))

        (re-matches #"\d+w" s)
        (let [n (parse-long (subs s 0 (dec (count s))))]
          (Date/from (.minus (Instant/now) (Duration/ofDays (* 7 n)))))

        (re-matches #"\d+m" s)
        (let [n (parse-long (subs s 0 (dec (count s))))
              date (.minus (LocalDate/now ZoneOffset/UTC) (Period/ofMonths n))]
          (Date/from (.toInstant (.atStartOfDay date ZoneOffset/UTC))))

        (re-matches #"\d{4}-\d{2}-\d{2}" s)
        (let [date (LocalDate/parse s)]
          (Date/from (.toInstant (.atStartOfDay date ZoneOffset/UTC))))

        (re-matches #"\d{4}-\d{2}-\d{2}T.+" s)
        (Date/from (Instant/parse s))

        :else
        (throw (IllegalArgumentException. (str "Invalid date param: " s)))))))
