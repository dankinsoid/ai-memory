;; @ai-generated(guided)
(ns ai-memory.service.migration
  "Export/import snapshots for migrating data between backends.

   Export produces a ZIP archive:
     snapshot.edn   — facts, edges, tick, tags (EDN format)
     vectors.edn    — embedding vectors (optional, separate for size)
     blobs/         — blob directory tree (files as-is)

   snapshot.edn format (namespaced keys matching store protocol):
   {:version 2
    :tick    N
    :tags    [{:tag/name \"foo\" :tag/tier :aspect} ...]
    :nodes   [{:db/id 42 :node/content \"...\" :node/weight 0.5 :node/cycle 30
               :node/tags [\"t1\" \"t2\"] :node/blob-dir \"...\" ...} ...]
    :edges   [{:edge/from 42 :edge/to 17
               :edge/weight 0.3 :edge/cycle 10 :edge/type :co-occurrence} ...]
    :vectors [{:db/id 42 :vector [...] :payload {...}} ...]}

   On import, nodes are matched by content for merge (upsert semantics).
   :db/id values from the source are used only to resolve internal references
   (edges → nodes, vectors → nodes) — target database assigns new EIDs.
   Tick cycles are adjusted to preserve fact ages across both existing
   and imported data."
  (:require [ai-memory.store.protocols :as p]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log])
  (:import [java.io ByteArrayOutputStream ByteArrayInputStream]
           [java.util.zip ZipOutputStream ZipInputStream ZipEntry]
           [java.util Date]
           [java.text SimpleDateFormat]))

;; ---------------------------------------------------------------------------
;; Date helpers
;; ---------------------------------------------------------------------------

(defn- parse-date
  "Coerces a value to java.util.Date. Accepts Date, ISO-8601 string, or nil."
  [v]
  (cond
    (instance? Date v) v
    (string? v)        (.parse (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss") v)
    :else              nil))

;; ---------------------------------------------------------------------------
;; Blob I/O helpers
;; ---------------------------------------------------------------------------

(defn- collect-all-blob-files
  "Recursively collects all files under blob-path (the entire blobs directory).
   Returns seq of {:relative-path \"blobs/...\" :file java.io.File}."
  [blob-path]
  (let [dir (io/file blob-path)]
    (when (.isDirectory dir)
      (->> (file-seq dir)
           (filter #(.isFile %))
           (mapv (fn [f]
                   (let [rel (.relativize (.toPath dir) (.toPath f))]
                     {:relative-path (str "blobs/" rel)
                      :file          f})))))))

(defn- write-blob-files!
  "Writes blob files from a map of {relative-path → byte-array} to disk.
   Paths are relative to blob-path (e.g. \"projects/ai-memory/2026-03-03_foo/meta.edn\")."
  [blob-path blob-files]
  (doseq [[rel-path content-bytes] blob-files]
    (let [f (io/file blob-path rel-path)]
      (.mkdirs (.getParentFile f))
      (io/copy (ByteArrayInputStream. content-bytes) f)))
  (count blob-files))

;; ---------------------------------------------------------------------------
;; ZIP packaging
;; ---------------------------------------------------------------------------

(defn- snapshot->zip-bytes
  "Packages snapshot map + entire blob directory + optional vectors into a ZIP byte array.
   `snapshot`  — the snapshot map (will be serialized as snapshot.edn)
   `vectors`   — seq of vector maps, or nil (written as separate vectors.edn)
   `blob-path` — filesystem base path for blobs (included in full)"
  [snapshot vectors blob-path]
  (let [baos (ByteArrayOutputStream.)]
    (with-open [zos (ZipOutputStream. baos)]
      ;; 1. Write snapshot.edn (facts, edges, tags — no vectors)
      (.putNextEntry zos (ZipEntry. "snapshot.edn"))
      (let [edn-bytes (.getBytes (pr-str snapshot) "UTF-8")]
        (.write zos edn-bytes 0 (count edn-bytes)))
      (.closeEntry zos)
      ;; 2. Write vectors.edn separately — large 1536-dim float arrays
      ;;    compress much better as a standalone entry and don't bloat
      ;;    the snapshot that users might want to inspect.
      (when (seq vectors)
        (.putNextEntry zos (ZipEntry. "vectors.edn"))
        (let [edn-bytes (.getBytes (pr-str vectors) "UTF-8")]
          (.write zos edn-bytes 0 (count edn-bytes)))
        (.closeEntry zos))
      ;; 3. Write entire blob directory tree
      (doseq [{:keys [relative-path file]} (collect-all-blob-files blob-path)]
        (.putNextEntry zos (ZipEntry. relative-path))
        (io/copy file zos)
        (.closeEntry zos)))
    (.toByteArray baos)))

(defn- zip-bytes->parts
  "Reads a ZIP byte array and returns {:snapshot <map> :vectors <vec> :blob-files {rel-path → bytes}}.
   Expects snapshot.edn and optional vectors.edn entries.
   blob-files paths are relative to blob-path (without 'blobs/' prefix)."
  [^bytes zip-bytes]
  (with-open [zis (ZipInputStream. (ByteArrayInputStream. zip-bytes))]
    (loop [snapshot nil
           vectors nil
           blob-files {}]
      (if-let [entry (.getNextEntry zis)]
        (let [name (.getName entry)
              content (.readAllBytes zis)]
          (.closeEntry zis)
          (cond
            (= name "snapshot.edn")
            (recur (edn/read-string (String. content "UTF-8")) vectors blob-files)

            (= name "vectors.edn")
            (recur snapshot (edn/read-string (String. content "UTF-8")) blob-files)

            (.startsWith name "blobs/")
            (recur snapshot vectors (assoc blob-files (subs name 6) content))

            :else
            (recur snapshot vectors blob-files)))
        {:snapshot snapshot :vectors vectors :blob-files blob-files}))))

;; ---------------------------------------------------------------------------
;; Export
;; ---------------------------------------------------------------------------

(defn- build-snapshot-map
  "Builds the snapshot data map (without blobs — those go in the ZIP separately).
   Returns {:snapshot map, :vectors vec-or-nil}."
  [ctx {:keys [include-vectors] :or {include-vectors true}}]
  (let [fs   (:fact-store ctx)
        vs   (:vector-store ctx)
        tick (p/current-tick fs)

        node-eids (p/all-nodes fs)
        eid-set   (set node-eids)

        nodes (mapv #(p/find-node fs %) node-eids)

        all-tags (p/all-tags fs)
        tags-export (->> all-tags
                         (filter :tag/tier)
                         (mapv #(dissoc % :tag/node-count)))

        all-edges (p/all-edges fs)
        edges (->> all-edges
                   (filter #(and (eid-set (:edge/from %)) (eid-set (:edge/to %))))
                   (mapv #(dissoc % :db/id :edge/id)))

        vectors (when include-vectors
                  (->> (p/scroll-all vs)
                       (filter #(eid-set (:id %)))
                       (mapv #(-> % (assoc :db/id (:id %)) (dissoc :id)))))

        snapshot {:version 2
                  :tick    tick
                  :tags    tags-export
                  :nodes   nodes
                  :edges   edges}]

    (log/info "Exported snapshot:" (count nodes) "nodes,"
              (count edges) "edges,"
              (if include-vectors (str (count vectors) " vectors,") "no vectors,")
              "tick=" tick)

    (cond-> {:snapshot snapshot}
      include-vectors (assoc :vectors vectors))))

(defn export-snapshot-zip
  "Exports database state as a ZIP byte array containing snapshot.edn + blobs/.
   `ctx`  — service context with :fact-store, :vector-store, :blob-path.
   `opts` — {:include-vectors true/false} (default true).
   Returns byte array of the ZIP archive."
  ([ctx] (export-snapshot-zip ctx {}))
  ([ctx opts]
   (let [{:keys [snapshot vectors]} (build-snapshot-map ctx opts)
         blob-path (:blob-path ctx)]
     (snapshot->zip-bytes snapshot vectors blob-path))))

;; ---------------------------------------------------------------------------
;; Import
;; ---------------------------------------------------------------------------

(defn- build-content-index
  "Builds a content → {:eid E :updated-at T} map of all existing nodes.
   Used for merge: match by content, then compare updated-at to pick winner."
  [fs]
  (reduce (fn [m eid]
            (if-let [node (p/find-node fs eid)]
              (assoc m (:node/content node)
                     {:eid        eid
                      :updated-at (:node/updated-at node)})
              m))
          {}
          (p/all-nodes fs)))

(defn- adjust-existing-cycles!
  "Shifts cycles of all existing nodes and edges forward by `delta` ticks.
   Preserves their relative ages when the imported snapshot has a higher tick."
  [fs delta]
  (when (pos? delta)
    (log/info "Adjusting existing fact cycles by +" delta)
    ;; Adjust node cycles
    (doseq [eid (p/all-nodes fs)]
      (when-let [node (p/find-node fs eid)]
        (let [old-cycle (or (:node/cycle node) 0)]
          (p/update-node-cycle! fs eid (+ old-cycle delta)))))
    ;; Adjust edge cycles
    (doseq [e (p/all-edges fs)]
      (let [old-cycle (or (:edge/cycle e) 0)]
        (p/update-edge-cycle! fs (:edge/id e) (+ old-cycle delta))))))

(defn- import-snapshot-map!
  "Imports a snapshot map into the current database with merge semantics.
   Nodes matched by content are updated; new nodes are created.
   Tick cycles are adjusted to preserve fact ages for both existing
   and imported data.

   `ctx`      — service context with :fact-store, :vector-store.
   `snapshot` — parsed snapshot map (no vectors key).
   `vectors`  — seq of vector maps, or nil.
   Returns {:imported-nodes N :imported-edges N :imported-vectors N :new-tick N}."
  [ctx snapshot vectors]
  (let [fs   (:fact-store ctx)
        vs   (:vector-store ctx)
        {:keys [tick nodes edges tags]} snapshot

        target-tick    (p/current-tick fs)
        new-tick       (max target-tick tick)
        import-delta   (- new-tick tick)          ;; shift for imported facts
        existing-delta (- new-tick target-tick)    ;; shift for existing facts

        _ (log/info "Import: snapshot-tick=" tick "target-tick=" target-tick
                    "new-tick=" new-tick "import-delta=" import-delta
                    "existing-delta=" existing-delta)

        ;; Step 1: Adjust existing facts' cycles (if target had data)
        _ (adjust-existing-cycles! fs existing-delta)

        ;; Step 2: Ensure all tags exist, set tiers
        _ (doseq [{:keys [tag/name]} tags]
            (p/ensure-tag! fs name))
        _ (doseq [n nodes
                  tag-name (:node/tags n)]
            (p/ensure-tag! fs tag-name))

        ;; Step 3: Build content index for merge matching
        content->eid (build-content-index fs)

        ;; Step 4: Import nodes, build old-eid → new-eid mapping
        old->new (reduce
                   (fn [m node]
                     (let [old-eid        (:db/id node)
                           content        (:node/content node)
                           weight         (:node/weight node)
                           cycle          (:node/cycle node)
                           tags           (:node/tags node)
                           blob-dir       (:node/blob-dir node)
                           adjusted-cycle (+ (or cycle 0) import-delta)
                           existing       (get content->eid content)
                           existing-eid   (:eid existing)]
                       (if existing-eid
                         ;; Merge: keep the more recently updated version
                         (let [imp-date      (parse-date (:node/updated-at node))
                               ext-date      (:updated-at existing)
                               import-newer? (or (nil? ext-date)
                                                 (and imp-date
                                                      (.after imp-date ext-date)))]
                           (p/update-node-cycle! fs existing-eid adjusted-cycle)
                           (when import-newer?
                             (p/update-node-weight! fs existing-eid (or weight 0.0))
                             (when (seq tags)
                               (p/replace-node-tags! fs existing-eid tags))
                             (when blob-dir
                               (p/set-node-blob-dir! fs existing-eid blob-dir)))
                           (assoc m old-eid existing-eid))
                         ;; New: create via import-node!
                         (let [result (p/import-node! fs
                                        {:content    content
                                         :weight     (or weight 0.0)
                                         :cycle      adjusted-cycle
                                         :tags       tags
                                         :blob-dir   blob-dir
                                         :sources    (:node/sources node)
                                         :session-id (:node/session-id node)
                                         :created-at (:node/created-at node)
                                         :updated-at (:node/updated-at node)})]
                           (assoc m old-eid (:id result))))))
                   {}
                   nodes)

        ;; Step 5: Import edges
        edges-imported
        (reduce (fn [cnt edge]
                  (let [from-eid (old->new (:edge/from edge))
                        to-eid   (old->new (:edge/to edge))]
                    (if (and from-eid to-eid)
                      (let [adjusted-cycle (+ (or (:edge/cycle edge) 0) import-delta)]
                        ;; Skip if edge already exists between these nodes
                        (when-not (p/find-edge-between fs from-eid to-eid)
                          (p/import-edge! fs {:from   from-eid
                                              :to     to-eid
                                              :weight (or (:edge/weight edge) 0.0)
                                              :cycle  adjusted-cycle
                                              :type   (:edge/type edge)}))
                        (inc cnt))
                      cnt)))
                0
                edges)

        ;; Step 6: Import vectors with new entity IDs
        vectors-imported
        (if (seq vectors)
          (reduce (fn [cnt v]
                    (if-let [new-eid (old->new (:db/id v))]
                      (do (p/upsert! vs new-eid (:vector v) (:payload v))
                          (inc cnt))
                      cnt))
                  0
                  vectors)
          0)

        ;; Step 7: Set tick and reconcile tag counts
        _ (p/set-tick! fs new-tick)
        _ (p/reconcile-tag-counts! fs)]

    (log/info "Import complete:" (count nodes) "nodes processed,"
              edges-imported "edges," vectors-imported "vectors, tick=" new-tick)

    {:imported-nodes   (count nodes)
     :imported-edges   edges-imported
     :imported-vectors vectors-imported
     :new-tick         new-tick}))

(defn import-snapshot-zip!
  "Imports a ZIP archive (as byte array) into the current database.
   Unpacks snapshot.edn + blob files, then delegates to import logic.
   `ctx`       — service context with :fact-store, :vector-store, :blob-path.
   `zip-bytes` — byte array of the ZIP archive.
   Returns {:imported-nodes N :imported-edges N :imported-vectors N :imported-blobs N :new-tick N}."
  [ctx zip-bytes]
  (let [{:keys [snapshot vectors blob-files]} (zip-bytes->parts zip-bytes)
        blob-path (:blob-path ctx)
        blobs-written (when (and blob-path (seq blob-files))
                        (write-blob-files! blob-path blob-files))
        result (import-snapshot-map! ctx snapshot vectors)]
    (assoc result :imported-blobs (or blobs-written 0))))
