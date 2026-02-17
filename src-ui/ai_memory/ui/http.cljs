(ns ai-memory.ui.http)

(defn GET [url callback]
  (-> (js/fetch url)
      (.then (fn [resp] (.json resp)))
      (.then (fn [json] (callback (js->clj json :keywordize-keys true))))
      (.catch (fn [err] (js/console.error "GET" url err)))))

(defn POST [url body callback]
  (-> (js/fetch url (clj->js {:method  "POST"
                               :headers {"Content-Type" "application/json"}
                               :body    (js/JSON.stringify (clj->js body))}))
      (.then (fn [resp] (.json resp)))
      (.then (fn [json] (callback (js->clj json :keywordize-keys true))))
      (.catch (fn [err] (js/console.error "POST" url err)))))
