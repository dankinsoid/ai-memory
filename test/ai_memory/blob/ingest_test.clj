(ns ai-memory.blob.ingest-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [ai-memory.blob.ingest :as ingest]))

(def ^:dynamic *temp-dir* nil)

(defn with-temp-dir [f]
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "ingest-test-" (System/nanoTime)))]
    (.mkdirs dir)
    (binding [*temp-dir* (.getPath dir)]
      (try
        (f)
        (finally
          (doseq [file (reverse (file-seq dir))]
            (.delete file)))))))

(use-fixtures :each with-temp-dir)

(defn- write-jsonl! [filename lines]
  (let [path (str *temp-dir* "/" filename)]
    (spit path (apply str (map #(str (json/generate-string %) "\n") lines)))
    path))

(deftest parse-session-test
  (testing "extracts user/assistant text messages, skips noise"
    (let [path (write-jsonl! "test.jsonl"
                 [{:type "queue-operation" :operation "dequeue"}
                  {:type "file-history-snapshot" :snapshot {}}
                  {:type "user"
                   :message {:role "user"
                             :content [{:type "text" :text "Hello"}]}}
                  {:type "assistant"
                   :message {:role "assistant"
                             :content [{:type "thinking" :text "hmm"}]}}
                  {:type "assistant"
                   :message {:role "assistant"
                             :content [{:type "text" :text "Hi there!"}]}}
                  {:type "assistant"
                   :message {:role "assistant"
                             :content [{:type "tool_use" :name "read"}]}}
                  {:type "user"
                   :message {:role "user"
                             :content [{:type "tool_result" :content "ok"}]}}])
          turns (ingest/parse-session path)]
      (is (= 2 (count turns)))
      (is (= "user" (:role (first turns))))
      (is (= "Hello" (:text (first turns))))
      (is (= "assistant" (:role (second turns))))
      (is (= "Hi there!" (:text (second turns)))))))

(deftest parse-session-strips-system-tags-test
  (testing "removes system-reminder and ide tags from text"
    (let [path (write-jsonl! "tags.jsonl"
                 [{:type "user"
                   :message {:role "user"
                             :content [{:type "text"
                                        :text "<system-reminder>ignore</system-reminder>Real question"}]}}])
          turns (ingest/parse-session path)]
      (is (= "Real question" (:text (first turns)))))))

(deftest format-section-test
  (testing "formats turns as markdown"
    (let [turns [{:role "user" :text "How does it work?"}
                 {:role "assistant" :text "Like this."}]
          result (ingest/format-section turns)]
      (is (= "**User**: How does it work?\n\n**Assistant**: Like this." result)))))

(deftest split-by-boundaries-test
  (testing "splits turns by provided boundaries"
    (let [turns (mapv #(hash-map :role "user" :text (str "msg-" %)) (range 10))
          boundaries [{:start_turn 0 :end_turn 3 :summary "First part"}
                      {:start_turn 4 :end_turn 9 :summary "Second part"}]
          sections (ingest/split-by-boundaries turns boundaries)]
      (is (= 2 (count sections)))
      (is (= "First part" (:summary (first sections))))
      (is (= 4 (count (:turns (first sections)))))
      (is (= 6 (count (:turns (second sections))))))))

(deftest split-auto-test
  (testing "auto-splits by turn count"
    (let [turns (mapv #(hash-map :role "user" :text (str %)) (range 20))
          sections (ingest/split-auto turns 5)]
      ;; 20 turns / (5*2=10 per chunk) = 2 sections
      (is (= 2 (count sections)))
      (is (= 10 (count (:turns (first sections))))))))
