(ns ai-memory.ui.core
  (:require [reagent.dom.client :as rdc]
            [ai-memory.ui.graph :as graph]))

(defonce root (atom nil))

(defn app []
  [:div [graph/graph-view]])

(defn ^:export init []
  (when-not @root
    (reset! root (rdc/create-root (.getElementById js/document "app"))))
  (rdc/render @root [app]))
