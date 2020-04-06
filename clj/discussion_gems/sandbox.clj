(ns discussion-gems.sandbox
  (:require [sparkling.core :as spark]
            [manifold.deferred :as mfd]
            [sparkling.conf :as conf]
            [discussion-gems.utils.spark :as uspark])
  (:import (org.apache.hadoop.io.compress ZStandardCodec)))

;; Reading the raw source files

(comment

  @(uspark/run-local
     (fn [sc]
       (->>
         (spark/text-file sc "deps.edn")
         (spark/take 3))))

  @(mfd/future
     (spark/with-context
       sc (-> (conf/spark-conf)
            (conf/master "local[*]")
            (conf/app-name "discussion-gems-local")
            (conf/set {"spark.driver.allowMultipleContexts" "true"})
            (conf/set {"spark.driver.allowMultipleContexts" "true"}))
       (->>
         (spark/text-file sc "../pushshift/reddit/comments/RC_2019-08.zst")
         (spark/take 3))))


  *e)
