(ns ai-memory.ui.graph
  (:require ["d3" :as d3]
            [reagent.core :as r]))

(def type-colors
  {"fact"       "#4fc3f7"
   "decision"   "#ab47bc"
   "preference" "#66bb6a"
   "pattern"    "#ffa726"
   "project"    "#ef5350"
   "domain"     "#26c6da"})

(defn- truncate [s n]
  (if (> (count s) n)
    (str (subs s 0 n) "…")
    s))

(defn- drag-behavior [simulation]
  (-> (d3/drag)
      (.on "start" (fn [event d]
                     (when (zero? (.-active event))
                       (.alphaTarget simulation 0.3)
                       (.restart simulation))
                     (set! (.-fx d) (.-x d))
                     (set! (.-fy d) (.-y d))))
      (.on "drag" (fn [event d]
                    (set! (.-fx d) (.-x event))
                    (set! (.-fy d) (.-y event))))
      (.on "end" (fn [event d]
                   (when (zero? (.-active event))
                     (.alphaTarget simulation 0)
                     (.restart simulation))
                   (set! (.-fx d) nil)
                   (set! (.-fy d) nil)))))

(defn- build-graph [el data]
  (let [width   (.-innerWidth js/window)
        height  (.-innerHeight js/window)
        svg     (-> (d3/select el)
                    (.attr "width" width)
                    (.attr "height" height))
        _       (.selectAll svg "*")  ; clear
        g       (.append svg "g")
        zoom    (-> (d3/zoom)
                    (.scaleExtent #js [0.1 8])
                    (.on "zoom" (fn [event]
                                  (.attr g "transform" (.-transform event)))))
        _       (.call svg zoom)
        nodes   (clj->js (:nodes data))
        links   (clj->js (:links data))
        sim     (-> (d3/forceSimulation nodes)
                    (.force "link" (-> (d3/forceLink links)
                                       (.id (fn [d] (.-id d)))
                                       (.distance (fn [d]
                                                    (/ 120 (max (.-weight d) 0.1))))))
                    (.force "charge" (-> (d3/forceManyBody)
                                         (.strength -200)))
                    (.force "center" (d3/forceCenter (/ width 2) (/ height 2)))
                    (.force "collision" (-> (d3/forceCollide)
                                            (.radius 30))))
        ;; tooltip
        tooltip (-> (d3/select "body")
                    (.append "div")
                    (.attr "class" "graph-tooltip")
                    (.style "position" "absolute")
                    (.style "visibility" "hidden")
                    (.style "background" "rgba(0,0,0,0.85)")
                    (.style "color" "#eee")
                    (.style "padding" "8px 12px")
                    (.style "border-radius" "4px")
                    (.style "font-size" "13px")
                    (.style "max-width" "300px")
                    (.style "pointer-events" "none")
                    (.style "z-index" "10"))
        ;; edges
        link-el (-> (.append g "g")
                    (.selectAll "line")
                    (.data links)
                    (.join "line")
                    (.attr "stroke" "#555")
                    (.attr "stroke-opacity" (fn [d] (min 1.0 (max 0.2 (.-weight d)))))
                    (.attr "stroke-width" (fn [d] (+ 1 (* 2 (.-weight d))))))
        ;; nodes
        node-el (-> (.append g "g")
                    (.selectAll "circle")
                    (.data nodes)
                    (.join "circle")
                    (.attr "r" (fn [d] (+ 6 (* 4 (or (.-weight d) 1)))))
                    (.attr "fill" (fn [d]
                                    (get type-colors (.-type d) "#999")))
                    (.attr "stroke" "#fff")
                    (.attr "stroke-width" 1.5)
                    (.on "mouseover" (fn [event d]
                                       (-> tooltip
                                           (.style "visibility" "visible")
                                           (.html (str "<b>" (.-type d) "</b><br>"
                                                       (.-content d))))))
                    (.on "mousemove" (fn [event _d]
                                       (-> tooltip
                                           (.style "top" (str (- (.-pageY event) 10) "px"))
                                           (.style "left" (str (+ (.-pageX event) 15) "px")))))
                    (.on "mouseout" (fn [_event _d]
                                      (.style tooltip "visibility" "hidden")))
                    (.call (drag-behavior sim)))
        ;; labels
        label-el (-> (.append g "g")
                     (.selectAll "text")
                     (.data nodes)
                     (.join "text")
                     (.text (fn [d] (truncate (or (.-content d) "") 30)))
                     (.attr "font-size" "11px")
                     (.attr "fill" "#ccc")
                     (.attr "dx" 12)
                     (.attr "dy" 4))]
    ;; tick
    (.on sim "tick"
         (fn []
           (-> link-el
               (.attr "x1" (fn [d] (.. d -source -x)))
               (.attr "y1" (fn [d] (.. d -source -y)))
               (.attr "x2" (fn [d] (.. d -target -x)))
               (.attr "y2" (fn [d] (.. d -target -y))))
           (-> node-el
               (.attr "cx" (fn [d] (.-x d)))
               (.attr "cy" (fn [d] (.-y d))))
           (-> label-el
               (.attr "x" (fn [d] (.-x d)))
               (.attr "y" (fn [d] (.-y d))))))))

(defn- fetch-graph [callback]
  (-> (js/fetch "/api/graph")
      (.then (fn [resp] (.json resp)))
      (.then (fn [json] (callback (js->clj json :keywordize-keys true))))
      (.catch (fn [err] (js/console.error "Failed to fetch graph:" err)))))

(defn graph-view []
  (let [svg-ref     (atom nil)
        poll-handle (atom nil)]
    (r/create-class
      {:display-name "graph-view"
       :component-did-mount
       (fn [_this]
         (fetch-graph
           (fn [data]
             (when @svg-ref
               (build-graph @svg-ref data))))
         (reset! poll-handle
           (js/setInterval
             (fn []
               (fetch-graph
                 (fn [data]
                   (when @svg-ref
                     (build-graph @svg-ref data)))))
             5000)))
       :component-will-unmount
       (fn [_this]
         (when-let [h @poll-handle] (js/clearInterval h)))
       :reagent-render
       (fn []
         [:svg {:ref (fn [el] (reset! svg-ref el))
                :style {:width "100vw" :height "100vh"}}])})))
