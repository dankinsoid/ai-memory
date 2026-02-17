(ns ai-memory.blob.ingest
  "Session JSONL parsing: reads ~/.claude session files, extracts user/assistant
   text messages, formats as clean markdown sections."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- text-message?
  "Returns true if the JSONL line is a user or assistant text message."
  [{:keys [type message]}]
  (and (#{"user" "assistant"} type)
       (= (:role message) (str type))
       (some #(= (:type %) "text") (:content message))))

(defn- extract-text
  "Extracts text content blocks from a message, discarding tool_use/thinking/etc."
  [message]
  (->> (:content message)
       (filter #(= (:type %) "text"))
       (map :text)
       (remove str/blank?)
       (str/join "\n\n")))

(defn- strip-system-tags
  "Removes <system-reminder>, <ide_*>, and similar system tags from text."
  [text]
  (-> text
      (str/replace #"<system-reminder>[\s\S]*?</system-reminder>" "")
      (str/replace #"<ide_[^>]*>[\s\S]*?</ide_[^>]*>" "")
      (str/replace #"<user-prompt-submit-hook>[\s\S]*?</user-prompt-submit-hook>" "")
      str/trim))

(defn parse-session
  "Reads a JSONL session file and returns a vec of {:role :text} maps.
   Filters to user/assistant text messages only."
  [session-path]
  (with-open [rdr (io/reader session-path)]
    (->> (line-seq rdr)
         (map #(json/parse-string % true))
         (filter text-message?)
         (mapv (fn [{:keys [type message]}]
                 {:role type
                  :text (-> message extract-text strip-system-tags)})))))

(defn- format-turn [{:keys [role text]}]
  (str "**" (str/capitalize role) "**: " text))

(defn format-section
  "Formats a sequence of turns into a readable markdown section."
  [turns]
  (str/join "\n\n" (map format-turn turns)))

(defn split-by-boundaries
  "Splits turns into sections using agent-provided boundaries.
   Each boundary is {:start_turn N :end_turn M :summary S}.
   Turn indices are 0-based into the turns vector."
  [turns boundaries]
  (mapv (fn [{:keys [start_turn end_turn summary]}]
          {:summary summary
           :turns   (subvec turns
                            (min start_turn (count turns))
                            (min (inc end_turn) (count turns)))})
        boundaries))

(defn split-auto
  "Auto-splits turns into sections of ~turns-per-section user/assistant pairs."
  [turns turns-per-section]
  (let [chunks (partition-all (* 2 turns-per-section) turns)]
    (vec (map-indexed
           (fn [i chunk]
             {:summary (str "Section " (inc i))
              :turns   (vec chunk)})
           (vec chunks)))))

(defn resolve-session-path
  "Finds the JSONL file for a session ID under ~/.claude/projects/.
   `project-path` — absolute project path, e.g. '/Users/danil/Code/ai-memory'.
   Returns the JSONL file path or nil."
  [session-id project-path]
  (let [encoded (str/replace project-path "/" "-")
        base    (io/file (System/getProperty "user.home")
                         ".claude" "projects" encoded)
        jsonl   (io/file base (str session-id ".jsonl"))]
    (when (.exists jsonl)
      (.getPath jsonl))))
