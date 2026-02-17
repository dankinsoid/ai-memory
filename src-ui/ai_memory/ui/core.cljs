(ns ai-memory.ui.core
  (:require [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [ai-memory.ui.graph :as graph]
            [ai-memory.ui.tags :as tags]))

(defonce root (atom nil))
(defonce current-view (r/atom :tags))

(def tab-style
  {:padding      "10px 24px"
   :cursor       "pointer"
   :font-size    "14px"
   :font-weight  "500"
   :color        "#888"
   :border       "none"
   :background   "transparent"
   :border-bottom "2px solid transparent"
   :transition   "all 0.2s ease"})

(def tab-active-style
  (merge tab-style
         {:color         "#4fc3f7"
          :border-bottom "2px solid #4fc3f7"}))

(defn tab-bar []
  [:div {:style {:display      "flex"
                 :gap          "4px"
                 :padding      "0 16px"
                 :background   "#16213e"
                 :border-bottom "1px solid #0f3460"}}
   [:button {:style    (if (= @current-view :graph) tab-active-style tab-style)
             :on-click #(reset! current-view :graph)}
    "Graph"]
   [:button {:style    (if (= @current-view :tags) tab-active-style tab-style)
             :on-click #(reset! current-view :tags)}
    "Tags"]])

(defn app []
  [:div {:style {:height "100vh" :display "flex" :flex-direction "column"}}
   [tab-bar]
   [:div {:style {:flex 1 :overflow "hidden" :position "relative"}}
    (case @current-view
      :graph [graph/graph-view]
      :tags  [tags/tags-view])]])

(defn ^:export init []
  (when-not @root
    (reset! root (rdc/create-root (.getElementById js/document "app"))))
  (rdc/render @root [app]))
