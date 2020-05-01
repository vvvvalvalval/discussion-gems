(ns discussion-gems.server.remote-eval
  "A server for (unsecure!) evaluation of code over HTTP"
  (:require [aleph.http :as http]
            [muuntaja.core]
            [muuntaja.middleware :as content-neg]
            [ring.middleware.cors]))

(require 'sc.api) ;; FIXME

(defn eval-fn-form
  [{:as _eval-request
    code-str :remote-eval/code-str
    bindings :remote-eval/data-bindings}]
  (let [code (read-string code-str)
        bindings-sym (gensym "b")]
    `(fn [~bindings-sym]
       (let ~(into []
               (mapcat
                 (fn [k]
                   [(symbol (name k))
                    `(get ~bindings-sym '~k)]))
               (keys bindings))
         ~code))))

(defn eval-from-req
  [{:as eval-request
    requires :remote-eval/require
    bindings :remote-eval/data-bindings}]
  (run! require requires)
  (let [exec-fn (eval
                  (eval-fn-form eval-request))
        ret (exec-fn bindings)]
    ret))

(comment

  (eval-from-req
    {:remote-eval/data-bindings {'x 42 'y 38}
     :remote-eval/code-str (pr-str '(+ x y))})
  => 80

  *e)


(defn base-handler
  [req]
  {:status 200
   :body
   {:result (eval-from-req (:body-params req))}})


(def handler
  (-> base-handler
    (content-neg/wrap-format
      (-> muuntaja.core/default-options
        (assoc-in [:http :encode-response-body?] (constantly true))))
    (ring.middleware.cors/wrap-cors
      :access-control-allow-origin #"http://localhost:\d+"
      :access-control-allow-methods [:get :put :post :delete])))


(defonce server
  (http/start-server #'handler {:port 9000}))


(comment
  (require '[sc.api])

  (require
    '[clj-http.client :as htc]
    '[jsonista.core :as json])

  (htc/request
    {:method :post
     :url "http://localhost:9000"
     :content-type :json
     :accept :transit+json
     :as :transit+json
     :body
     (json/write-value-as-string
       {:remote-eval/data-bindings {'x 42 'y 38}
        :remote-eval/code-str (pr-str '(+ x y))})})

  ;; FROM THE command line:
  ;$ curl --header "Content-Type: application/json"  --header "Accept: application/json"   --request POST   --data '{"remote-eval/code-str": "(+ 2 3)"}' http://localhost:9000
  ;{"result":5}(base)
  *e)
