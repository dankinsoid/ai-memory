#!/usr/bin/env bb
;; Inserts #_end/name labels before closing brackets in Clojure source files.
;;
;; Usage:
;;   bb scripts/add-end-labels.bb src/foo.clj                        # print to stdout
;;   bb scripts/add-end-labels.bb src/foo.clj --in-place             # modify file
;;   bb scripts/add-end-labels.bb src/foo.clj --dry-run              # print to stdout (same as default)
;;   bb scripts/add-end-labels.bb src/foo.clj --line-threshold 5 --bracket-threshold 2
;;   bb scripts/add-end-labels.bb src/a.clj src/b.clj --in-place     # multiple files

(require '[rewrite-clj.zip :as z]
         '[rewrite-clj.node :as n]
         '[clojure.string :as str])

;;; ---------------------------------------------------------------------------
;;; Zipper helpers
;;; ---------------------------------------------------------------------------

(defn ws-tag?
  "True for whitespace-like zipper tags."
  [tag]
  (#{:whitespace :newline :comma} tag))

(defn first-content
  "First non-whitespace child of zloc, or nil."
  [zloc]
  ;; z/down in rewrite-clj already skips whitespace
  (z/down zloc))

(defn second-content
  "Second non-whitespace child of zloc, or nil."
  [zloc]
  (some-> zloc first-content z/right))

(defn rightmost-content
  "Last non-whitespace child of zloc, or nil."
  [zloc]
  ;; z/rightmost in rewrite-clj already skips whitespace
  (some-> zloc z/down z/rightmost))

(defn inside-uneval?
  "True if zloc is a descendant of an :uneval node."
  [zloc]
  (loop [cur (z/up zloc)]
    (cond
      (nil? cur)            false
      (= :uneval (z/tag cur)) true
      :else                 (recur (z/up cur)))))

(defn already-labeled?
  "True if the rightmost non-whitespace child of zloc is #_end/... uneval node."
  [zloc]
  (when-let [rc (rightmost-content zloc)]
    (when (= :uneval (z/tag rc))
      (when-let [inner (z/down rc)]
        (and (= :token (z/tag inner))
             (str/starts-with? (z/string inner) "end/"))))))

(defn form-head
  "String of the first child token, or nil."
  [zloc]
  (some-> zloc first-content z/string))

(defn form-name
  "Returns the label name string for zloc, or nil to skip this form.
   - fn forms return nil (skip entirely)
   - defn/defmacro/defmethod/defmulti etc. return \"head-second\"
   - other forms return just \"head\""
  [zloc]
  (let [head (form-head zloc)]
    (cond
      (nil? head) nil
      (= "fn" head) nil
      (#{"defn" "defn-" "defmacro" "defmulti" "defmethod"
         "defprotocol" "defrecord" "deftype" "defonce" "deftest"} head)
      (when-let [sc (second-content zloc)]
        (str head "-" (z/string sc)))
      :else head)))

;;; ---------------------------------------------------------------------------
;;; Collect pass — read-only walk
;;; ---------------------------------------------------------------------------

(defn collect-forms
  "Walk the full zipper tree (depth-first) and return a vector of maps:
   {:key [start-row start-col]
    :start-row :end-row
    :single-line? :fn? :already-labeled?}

   Skips forms inside :uneval nodes."
  [root]
  (loop [cur root
         result []]
    (if (z/end? cur)
      result
      (let [result
            (if (and (= :list (z/tag cur))
                     (not (inside-uneval? cur)))
              (let [m           (meta (z/node cur))
                    start-row   (:row m)
                    start-col   (:col m)
                    end-row     (:end-row m)
                    head        (form-head cur)]
                (conj result
                      {:key           [start-row start-col]
                       :name          (form-name cur)
                       :start-row     start-row
                       :end-row       end-row
                       :single-line?  (= start-row end-row)
                       :fn?           (= "fn" head)
                       :already-labeled? (boolean (already-labeled? cur))}))
              result)]
        (recur (z/next cur) result)))))

;;; ---------------------------------------------------------------------------
;;; Annotation criteria
;;; ---------------------------------------------------------------------------

(defn should-annotate?
  "Returns true if form should receive an #_end/... label.

   Criteria (ALL must be true):
   - form name is non-nil (not fn, not empty)
   - not single-line
   - not already labeled
   - span >= line-threshold OR co-closers >= bracket-threshold"
  [form all-forms {:keys [line-threshold bracket-threshold]}]
  (when (and (not (:fn? form))
             (not (:single-line? form))
             (not (:already-labeled? form))
             (:name form))
    (let [end-row       (:end-row form)
          span          (inc (- end-row (:start-row form)))
          ;; co-closers: forms (non-fn, non-single-line) ending on same end-row
          co-closers    (count (filter (fn [f]
                                         (and (= end-row (:end-row f))
                                              (not (:fn? f))
                                              (not (:single-line? f))))
                                       all-forms))]
      (or (>= span line-threshold)
          (>= co-closers bracket-threshold)))))

;;; ---------------------------------------------------------------------------
;;; Label node construction
;;; ---------------------------------------------------------------------------

(defn make-label-node
  "Creates the #_end/NAME uneval node."
  [name]
  (n/uneval-node [(n/token-node (symbol (str "end/" name)))]))

;;; ---------------------------------------------------------------------------
;;; Annotation pass
;;; ---------------------------------------------------------------------------

(defn insert-label
  "Inserts #_end/name label before the closing bracket of the list at zloc.
   Rewrite-clj auto-inserts a space between the last child and the label node.
   Returns a zloc positioned at the list node after modification."
  [zloc name]
  (let [rc       (rightmost-content zloc)
        label    (make-label-node name)
        inserted (z/insert-right rc label)]
    (z/up inserted)))

(defn annotate-source
  "Parse source, insert #_end/... labels according to opts, return modified source string."
  [source opts]
  (let [{:keys [line-threshold bracket-threshold]
         :or   {line-threshold    8
                bracket-threshold 3}} opts

        ;; Parse — start cursor at :forms root for complete traversal
        first-loc (z/of-string source {:track-position? true})
        root      (z/up first-loc)

        ;; Collect pass: gather info on all list forms
        all-forms (collect-forms root)

        ;; Build lookup: [start-row start-col] -> form info
        ;; Only include forms that should be annotated
        to-annotate (->> all-forms
                         (filter #(should-annotate? % all-forms opts))
                         (reduce (fn [m f] (assoc m (:key f) f)) {}))]

    (if (empty? to-annotate)
      source
      ;; Annotation pass: walk again, insert labels
      (loop [cur root]
        (if (z/end? cur)
          (z/root-string cur)
          (if (and (= :list (z/tag cur))
                   (not (inside-uneval? cur)))
            (let [m   (meta (z/node cur))
                  key [(:row m) (:col m)]]
              (if-let [form (to-annotate key)]
                ;; Annotate this form and continue from the (now-modified) list node
                (let [cur-after (insert-label cur (:name form))]
                  (recur (z/next cur-after)))
                (recur (z/next cur))))
            (recur (z/next cur))))))))

;;; ---------------------------------------------------------------------------
;;; CLI argument parsing
;;; ---------------------------------------------------------------------------

(defn parse-args
  "Parse *command-line-args* into {:files [...] :opts {...}}.
   Supported flags:
     --in-place
     --dry-run
     --line-threshold N
     --bracket-threshold N"
  [argv]
  (loop [args   argv
         files  []
         opts   {:in-place false :line-threshold 8 :bracket-threshold 3}]
    (if (empty? args)
      {:files files :opts opts}
      (let [arg (first args)
            rest-args (rest args)]
        (cond
          (= "--in-place" arg)
          (recur rest-args files (assoc opts :in-place true))

          (= "--dry-run" arg)
          ;; dry-run is the default (stdout); kept for clarity
          (recur rest-args files opts)

          (= "--line-threshold" arg)
          (recur (rest rest-args) files (assoc opts :line-threshold (parse-long (first rest-args))))

          (= "--bracket-threshold" arg)
          (recur (rest rest-args) files (assoc opts :bracket-threshold (parse-long (first rest-args))))

          :else
          (recur rest-args (conj files arg) opts))))))

;;; ---------------------------------------------------------------------------
;;; File processing
;;; ---------------------------------------------------------------------------

(defn process-file
  "Read file at path, annotate, and either write back (--in-place) or print to stdout.
   On any error, prints to stderr and continues (returns nil)."
  [path opts]
  (try
    (let [source (slurp path)
          result (try
                   (annotate-source source opts)
                   (catch Exception e
                     (binding [*out* *err*]
                       (println (str "ERROR annotating " path ": " (.getMessage e))))
                     ;; Return original source unchanged for --in-place safety
                     source))]
      (if (:in-place opts)
        (when (not= result source)
          (spit path result)
          (println (str "Updated: " path)))
        (print result)))
    (catch java.io.FileNotFoundException _
      (binding [*out* *err*]
        (println (str "ERROR: file not found: " path))))))

;;; ---------------------------------------------------------------------------
;;; Entry point
;;; ---------------------------------------------------------------------------

(let [{:keys [files opts]} (parse-args *command-line-args*)]
  (if (empty? files)
    (do
      (binding [*out* *err*]
        (println "Usage: bb scripts/add-end-labels.bb <file> [<file> ...] [--in-place] [--dry-run]")
        (println "       [--line-threshold N] [--bracket-threshold N]"))
      (System/exit 1))
    (doseq [path files]
      (process-file path opts))))
