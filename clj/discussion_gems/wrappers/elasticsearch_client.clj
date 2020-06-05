(ns discussion-gems.wrappers.elasticsearch-client
  (:require [qbits.spandex :as spandex]
            [manifold.deferred :as mfd])
  (:import (java.time Instant)
           (java.time.format DateTimeFormatter FormatStyle)))

;; ------------------------------------------------------------------------------
;; IO

(defn basic-client
  "A minimalc-configuration ElasticSearch client, using JSON encoding."
  [es-url]
  (spandex/client
    {:hosts [es-url]
     :default-headers {"Accept" "application/json"
                       "Content-Type" "application/json"}}))

(defn request
  "Runs Spandex the provided request asynchronously, returning a Manifold Deferred."
  [esc req]
  (let [d (mfd/deferred)]
    (spandex/request-async
      esc
      (assoc req
        :success (fn [resp] (mfd/success! d resp))
        :error (fn [err] (mfd/error! d err))))
    d))

;; ------------------------------------------------------------------------------
;; Encoding

(defn es-encode-instant [^Instant t]
  ;; Reference: https://www.elastic.co/guide/en/elasticsearch/reference/7.7/date.html
  (.format DateTimeFormatter/ISO_INSTANT t))


(comment
  (def t (Instant/now))

  (es-encode-instant t)
  => "2020-05-30T16:44:24.368Z"

  *e)

;; ------------------------------------------------------------------------------
;; Queries

(defn list-indices-names
  [esc]
  (-> @(request esc {:method :get :url [:_cat :indices]})
    :body
    (->> (map :index)
      sort
      vec)))