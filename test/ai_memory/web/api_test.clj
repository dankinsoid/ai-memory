(ns ai-memory.web.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai-memory.service.sessions]))

(def ^:private strip-injected-tags #'ai-memory.service.sessions/strip-injected-tags)
(def ^:private format-turn-as-markdown #'ai-memory.service.sessions/format-turn-as-markdown)

;; --- strip-injected-tags ---

(deftest strip-injected-tags-test
  (testing "strips system-reminder"
    (is (= "hello"
           (strip-injected-tags "hello<system-reminder>injected</system-reminder>"))))

  (testing "strips local-command-stdout"
    (is (= ""
           (strip-injected-tags "<local-command-stdout>| react-routing | Plugin | 54 |</local-command-stdout>"))))

  (testing "strips local-command-caveat"
    (is (= "real text"
           (strip-injected-tags "<local-command-caveat>DO NOT respond</local-command-caveat>\nreal text"))))

  (testing "strips ide_opened_file"
    (is (= ""
           (strip-injected-tags "<ide_opened_file>src/foo.clj</ide_opened_file>"))))

  (testing "does NOT strip unknown tags (not in allowlist)"
    (is (= "<my-component>hello</my-component>"
           (strip-injected-tags "<my-component>hello</my-component>"))))

  (testing "does NOT strip plain hyphenated tags not in list"
    (is (= "<custom-element>data</custom-element>"
           (strip-injected-tags "<custom-element>data</custom-element>"))))

  (testing "handles nil"
    (is (nil? (strip-injected-tags nil))))

  (testing "handles multiline content inside tag"
    (is (= "before  after"
           (strip-injected-tags "before <system_instruction>line1\nline2\nline3</system_instruction> after")))))

;; --- format-turn-as-markdown ---

(deftest format-turn-as-markdown-test
  (testing "strips injected tags from user message"
    (let [messages [{:role "user"
                     :content [{:type "text"
                                :text "<local-command-stdout>| skill | 54 |</local-command-stdout>\nwhat is X?"}]}
                    {:role "assistant"
                     :content [{:type "text" :text "X is Y."}]}]
          result (format-turn-as-markdown messages)]
      (is (not (re-find #"local-command-stdout" result)))
      (is (re-find #"what is X\?" result))
      (is (re-find #"X is Y\." result))))

  (testing "skips message that becomes empty after stripping"
    (let [messages [{:role "user"
                     :content [{:type "text"
                                :text "<local-command-caveat>caveat only</local-command-caveat>"}]}
                    {:role "assistant"
                     :content [{:type "text" :text "response"}]}]
          result (format-turn-as-markdown messages)]
      (is (not (re-find #"caveat" result)))
      (is (re-find #"response" result)))))
