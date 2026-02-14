(ns ai-memory.ui.core
  (:require [reagent.dom :as rdom]))

(defn app []
  [:div {:style {:padding "2rem"}}
   [:h1 "ai-memory"]
   [:p "Graph visualization will be here."]])

(defn ^:export init []
  (rdom/render [app] (.getElementById js/document "app")))
