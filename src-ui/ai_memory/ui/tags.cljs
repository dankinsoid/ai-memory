(ns ai-memory.ui.tags
  (:require ["d3" :as d3]
            [reagent.core :as r]
            [ai-memory.ui.http :as http]))

(declare update-tree)

;; --- State ---

(defonce state (r/atom {:tree-data      nil
                        :selected       #{}
                        :facts          nil
                        :facts-loading? false}))

(defonce poll-handle (atom nil))

;; --- Colors ---

(def ^:private palette
  ["#4fc3f7" "#ffa726" "#ef5350" "#66bb6a" "#ab47bc" "#26c6da" "#ff7043" "#8d6e63"])

(defn- node-color [^js d]
  (let [name (.. d -data -name)]
    (if (= name "memory")
      "#888"
      (nth palette (mod (hash name) (count palette))))))

;; --- Data transform ---

(defn- api->hierarchy
  "Converts flat tag list [{:tag/name \"x\" :tag/node-count 5} ...] into D3 hierarchy."
  [tags]
  {:name     "memory"
   :path     "memory"
   :count    0
   :children (mapv (fn [tag]
                     {:name  (:tag/name tag)
                      :path  (:tag/name tag)
                      :count (or (:tag/node-count tag) 0)})
                   tags)})

;; --- Fetch ---

(defn- load-taxonomy! []
  (http/GET "/api/tags?limit=100"
    (fn [data]
      (swap! state assoc :tree-data (api->hierarchy data)))))

(defn- load-facts! [tag-paths]
  (if (empty? tag-paths)
    (swap! state assoc :facts nil :facts-loading? false)
    (do
      (swap! state assoc :facts-loading? true)
      (http/POST "/api/tags/facts"
        {:tag-sets [(vec tag-paths)] :limit 50}
        (fn [data]
          (swap! state assoc
                 :facts (get-in data [:results 0 :facts])
                 :facts-loading? false))))))

;; --- D3 Tree ---

(def ^:private tree-margin {:top 20 :right 120 :bottom 20 :left 80})
(def ^:private node-radius 6)

(defn- diagonal [^js s ^js d]
  (str "M" (.-y s) "," (.-x s)
       "C" (/ (+ (.-y s) (.-y d)) 2) "," (.-x s)
       " " (/ (+ (.-y s) (.-y d)) 2) "," (.-x d)
       " " (.-y d) "," (.-x d)))

(defn- has-children? [^js d]
  (or (.-children d) (unchecked-get d "_children")))

(defn- get-collapsed [^js d]
  (unchecked-get d "_children"))

(defn- set-collapsed! [^js d v]
  (unchecked-set d "_children" v))

(defn- build-tree [^js el tree-data selected on-select]
  (let [container-el (.closest el ".tags-tree-container")
        width   (if container-el (.-offsetWidth ^js container-el) (.-innerWidth js/window))
        height  (if container-el (.-offsetHeight ^js container-el) (- (.-innerHeight js/window) 200))
        svg     (-> (d3/select el)
                    (.attr "width" width)
                    (.attr "height" height))
        _       (-> svg (.selectAll "*") (.remove))
        g       (-> svg (.append "g")
                    (.attr "transform"
                           (str "translate(" (:left tree-margin) "," (:top tree-margin) ")")))
        zoom    (-> (d3/zoom)
                    (.scaleExtent #js [0.3 5])
                    (.on "zoom" (fn [^js event]
                                  (.attr g "transform" (.-transform event)))))
        _       (.call svg zoom)
        inner-w (- width (:left tree-margin) (:right tree-margin))
        inner-h (- height (:top tree-margin) (:bottom tree-margin))

        ;; Create hierarchy
        root    (-> (d3/hierarchy (clj->js tree-data))
                    ((fn [^js r]
                       ;; Collapse all children except first level
                       (.each r (fn [^js d]
                                  (when (and (.-children d) (> (.-depth d) 0))
                                    (set-collapsed! d (.-children d))
                                    (set! (.-children d) nil))))
                       r)))

        ;; Tree layout
        tree-layout (-> (d3/tree)
                        (.size #js [inner-h inner-w]))]

    ;; Store references for updates via JS properties
    (unchecked-set el "_root" root)
    (unchecked-set el "_g" g)
    (unchecked-set el "_tree" tree-layout)
    (unchecked-set el "_selected" selected)
    (unchecked-set el "_onSelect" on-select)
    (unchecked-set el "_innerW" inner-w)
    (unchecked-set el "_innerH" inner-h)

    ;; Initial render
    (update-tree el root)))

(defn- update-tree [^js el ^js source]
  (let [g           (unchecked-get el "_g")
        tree-layout (unchecked-get el "_tree")
        root        (unchecked-get el "_root")
        selected    (unchecked-get el "_selected")
        on-select   (unchecked-get el "_onSelect")
        duration    300

        ;; Recompute layout
        _         (.size tree-layout #js [(unchecked-get el "_innerH")
                                          (unchecked-get el "_innerW")])
        ^js tree-root (tree-layout root)
        nodes     (.descendants tree-root)
        links     (.links tree-root)

        ;; --- LINKS ---
        link-sel  (-> (.selectAll g "path.tree-link")
                      (.data links (fn [^js d] (.. d -target -data -path))))

        link-enter (-> (.enter link-sel)
                       (.insert "path" "g")
                       (.attr "class" "tree-link")
                       (.attr "fill" "none")
                       (.attr "stroke" "#334466")
                       (.attr "stroke-width" 1.5)
                       (.attr "d" (fn [_]
                                    (let [o #js {:x (.-x0 source) :y (.-y0 source)}]
                                      (diagonal o o)))))

        _link-update (-> (.merge link-enter link-sel)
                        (.transition)
                        (.duration duration)
                        (.attr "d" (fn [^js d] (diagonal (.-source d) (.-target d)))))

        _          (-> (.exit link-sel)
                       (.transition)
                       (.duration duration)
                       (.attr "d" (fn [_]
                                    (let [o #js {:x (.-x source) :y (.-y source)}]
                                      (diagonal o o))))
                       (.remove))

        ;; --- NODES ---
        node-sel  (-> (.selectAll g "g.tree-node")
                      (.data nodes (fn [^js d] (.. d -data -path))))

        node-enter (-> (.enter node-sel)
                       (.append "g")
                       (.attr "class" "tree-node")
                       (.attr "transform" (fn [_]
                                            (str "translate(" (or (.-y0 source) 0) ","
                                                                  (or (.-x0 source) 0) ")")))
                       (.attr "cursor" "pointer")
                       (.on "click" (fn [^js event ^js d]
                                      (.stopPropagation event)
                                      (if (.-altKey event)
                                        ;; Alt+click → select tag
                                        (when-let [path (.. d -data -path)]
                                          (on-select path))
                                        ;; Normal click → expand/collapse
                                        (do
                                          (if (.-children d)
                                            (do (set-collapsed! d (.-children d))
                                                (set! (.-children d) nil))
                                            (when (get-collapsed d)
                                              (set! (.-children d) (get-collapsed d))
                                              (set-collapsed! d nil)))
                                          (update-tree el d))))))

        _          (-> (.append node-enter "circle")
                       (.attr "r" node-radius)
                       (.attr "fill" (fn [^js d]
                                       (if (has-children? d)
                                         (node-color d) "#1a1a2e")))
                       (.attr "stroke" (fn [^js d] (node-color d)))
                       (.attr "stroke-width" 2))

        _          (-> (.append node-enter "text")
                       (.attr "dy" "0.35em")
                       (.attr "x" (fn [^js d] (if (has-children? d) -12 12)))
                       (.attr "text-anchor" (fn [^js d] (if (has-children? d) "end" "start")))
                       (.attr "fill" "#e0e0e0")
                       (.attr "font-size" "13px")
                       (.text (fn [^js d] (.. d -data -name))))

        _          (-> (.append node-enter "text")
                       (.attr "class" "count-badge")
                       (.attr "dy" "-0.8em")
                       (.attr "x" 0)
                       (.attr "text-anchor" "middle")
                       (.attr "fill" "#888")
                       (.attr "font-size" "10px")
                       (.text (fn [^js d]
                                (let [c (.. d -data -count)]
                                  (when (and c (pos? c)) (str c))))))

        _node-update (-> (.merge node-enter node-sel)
                         (.transition)
                         (.duration duration)
                         (.attr "transform" (fn [^js d]
                                              (str "translate(" (.-y d) "," (.-x d) ")"))))

        _          (-> (.merge node-enter node-sel)
                       (.select "circle")
                       (.attr "fill" (fn [^js d]
                                       (cond
                                         (and selected (contains? selected (.. d -data -path)))
                                         "#4fc3f7"
                                         (has-children? d)
                                         (node-color d)
                                         :else "#1a1a2e")))
                       (.attr "stroke" (fn [^js d]
                                         (if (and selected (contains? selected (.. d -data -path)))
                                           "#4fc3f7"
                                           (node-color d))))
                       (.attr "stroke-width" (fn [^js d]
                                               (if (and selected (contains? selected (.. d -data -path)))
                                                 3 2))))

        _          (-> (.exit node-sel)
                       (.transition)
                       (.duration duration)
                       (.attr "transform" (fn [_]
                                            (str "translate(" (.-y source) "," (.-x source) ")")))
                       (.remove))]

    ;; Store positions for next transition
    (.each nodes (fn [^js d]
                   (set! (.-x0 d) (.-x d))
                   (set! (.-y0 d) (.-y d))))))

;; --- Reagent Components ---

(defn- selected-bar []
  (let [{:keys [selected facts-loading?]} @state
        cnt (count selected)]
    (when (pos? cnt)
      [:div {:style {:padding    "8px 16px"
                     :background "#16213e"
                     :border-top "1px solid #0f3460"
                     :display    "flex"
                     :align-items "center"
                     :flex-wrap  "wrap"
                     :gap        "8px"}}
       [:span {:style {:color "#888" :font-size "12px" :margin-right "8px"}}
        "Selected:"]
       (for [path (sort selected)]
         ^{:key path}
         [:span {:style {:display       "inline-flex"
                         :align-items   "center"
                         :gap           "4px"
                         :padding       "2px 10px"
                         :background    "rgba(79, 195, 247, 0.15)"
                         :border        "1px solid rgba(79, 195, 247, 0.4)"
                         :border-radius "12px"
                         :font-size     "12px"
                         :color         "#4fc3f7"}}
          path
          [:span {:style    {:cursor "pointer" :opacity 0.6 :margin-left "2px"}
                  :on-click #(do (swap! state update :selected disj path)
                                 (load-facts! (disj (:selected @state) path)))}
           "\u00d7"]])
       (when facts-loading?
         [:span {:style {:color "#888" :font-size "12px"}} "loading..."])])))

(defn- fact-card [fact]
  (let [title     (or (:node/content fact) (:content fact) "")
        tags      (or (:node/tag-refs fact) (:tag-refs fact) [])
        weight    (or (:node/weight fact) (:weight fact))]
    [:div {:style {:background    "#16213e"
                   :border        "1px solid #0f3460"
                   :border-radius "8px"
                   :padding       "12px 16px"
                   :margin-bottom "8px"}}
     [:div {:style {:display "flex" :justify-content "space-between" :align-items "center"}}
      [:span {:style {:color "#e0e0e0" :font-size "14px"}} title]]
     (when (seq tags)
       [:div {:style {:margin-top "8px" :display "flex" :flex-wrap "wrap" :gap "4px"}}
        (for [tag tags]
          (let [tag-name (or (:tag/name tag) (when (string? tag) tag) (str tag))]
            ^{:key tag-name}
            [:span {:style {:padding       "1px 8px"
                            :background    "rgba(79, 195, 247, 0.1)"
                            :border-radius "10px"
                            :font-size     "11px"
                            :color         "#4fc3f7"}}
             (str "#" tag-name)]))])
     (when weight
       [:div {:style {:margin-top "4px" :font-size "11px" :color "#555"}}
        (str "weight: " (.toFixed weight 2))])]))

(defn- facts-panel []
  (let [{:keys [facts selected]} @state]
    (when (and (seq selected) facts)
      [:div {:style {:padding    "16px"
                     :overflow-y "auto"
                     :max-height "40vh"
                     :border-top "1px solid #0f3460"}}
       [:div {:style {:color "#888" :font-size "12px" :margin-bottom "8px"}}
        (str (count facts) " fact" (when (not= 1 (count facts)) "s") " found")]
       (if (seq facts)
         (for [fact facts]
           ^{:key (or (:node/id fact) (random-uuid))}
           [fact-card fact])
         [:div {:style {:color "#555" :font-size "13px" :padding "20px 0" :text-align "center"}}
          "No facts found for this tag combination"])])))

(defn- make-on-select []
  (fn [path]
    (let [sel     (:selected @state)
          new-sel (if (contains? sel path) (disj sel path) (conj sel path))]
      (swap! state assoc :selected new-sel)
      (load-facts! new-sel))))

(defn- tree-component []
  (let [svg-ref (atom nil)]
    (r/create-class
      {:display-name "taxonomy-tree"

       :component-did-mount
       (fn [_this]
         (load-taxonomy!)
         (add-watch state ::tree-watcher
           (fn [_ _ old new]
             (when (and @svg-ref
                        (or (not= (:tree-data old) (:tree-data new))
                            (not= (:selected old) (:selected new))))
               (when-let [data (:tree-data new)]
                 (try
                   (build-tree @svg-ref data (:selected new) (make-on-select))
                   (catch :default e
                     (js/console.error "build-tree watcher error:" e)))))))
)

       :component-will-unmount
       (fn [_this]
         (when-let [h @poll-handle] (js/clearInterval h))
         (remove-watch state ::tree-watcher))

       :reagent-render
       (fn []
         (let [{:keys [tree-data]} @state]
           [:div.tags-tree-container
            {:style {:flex     1
                     :overflow "hidden"
                     :position "relative"}}
            (if tree-data
              [:svg {:ref   (fn [el]
                              (when (and el (not @svg-ref))
                                (reset! svg-ref el)
                                (try
                                  (build-tree el (:tree-data @state) (:selected @state) (make-on-select))
                                  (catch :default e
                                    (js/console.error "build-tree error:" e)))))
                     :style {:width "100%" :height "100%"}}]
              [:div {:style {:color "#555" :text-align "center" :padding "40px"}}
               "Loading taxonomy..."])]))})))

(defn tags-view []
  [:div {:style {:height         "100%"
                 :display        "flex"
                 :flex-direction "column"
                 :overflow       "hidden"}}
   [:div {:style {:padding   "12px 16px 4px"
                  :color     "#888"
                  :font-size "12px"}}
    "Click to expand/collapse \u2022 Alt+click to select tag"]
   [tree-component]
   [selected-bar]
   [facts-panel]])
