(ns discussion-gems.lab-ui.welcome
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [rum.core :as rum]
            [cljs-http.client :as http]
            [cljs.core.async :refer [<!]]))

(enable-console-print!)



(println "hello lab! 42")

(rum/defc <welcome>
  []
  [:div
   [:h2 "Welcome!"]
   [:p "Comment ça va ?"]])

(defn main []
  ;; conditionally start the app based on whether the #main-app-area
  ;; node is on the page
  (when-let [node (.getElementById js/document "main-app-area")]
    (rum/mount
      (<welcome>)
      node)))

(comment
  (go
    (let [response (<! (http/post "http://localhost:9000"
                         {:headers {"Accept" "application/transit+json"}
                          :with-credentials? false
                          :transit-params
                          {:remote-eval/data-bindings {'x 42 'y 39}
                           :remote-eval/code-str (pr-str '(+ x y))}}))]
      (def resp response)))


  *e)