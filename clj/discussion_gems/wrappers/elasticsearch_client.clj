(ns discussion-gems.wrappers.elasticsearch-client
  (:require [qbits.spandex :as spandex]
            [manifold.deferred :as mfd]))

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
