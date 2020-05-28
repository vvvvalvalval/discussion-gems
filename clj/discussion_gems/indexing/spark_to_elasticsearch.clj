(ns discussion-gems.indexing.spark-to-elasticsearch
  (:require [mapdag.step :as mdg]
            [sparkling.core :as spark]
            [jsonista.core :as json]
            [mapdag.runtime.default :as mdg-run]
            [manifold.deferred :as mfd]
            [discussion-gems.utils.spark :as uspark]
            [sparkling.conf :as conf]
            [discussion-gems.data-sources :as dgds]
            [discussion-gems.indexing.elasticsearch-schema]
            [discussion-gems.wrappers.elasticsearch-client :as es])
  (:import (org.elasticsearch.spark.rdd.api.java JavaEsSpark)
           (org.apache.spark.api.java JavaRDD)))

(defn get-data-sources
  [sc]
  {:spark/context sc
   :reddit-docs-submissions (dgds/submissions-all-rdd sc)
   :reddit-docs-comments (dgds/comments-all-rdd sc)})

(defn get-md-sample-data-sources
  [sc]
  {:spark/context sc
   :reddit-docs-submissions (dgds/subm-md-sample-rdd sc)
   :reddit-docs-comments (dgds/comments-md-sample-rdd sc)})

(def dag_indexing
  {:raw-content-es-docs
   (mdg/step [:reddit-docs-submissions :reddit-docs-comments]
     (fn [reddit-docs-submissions reddit-docs-comments]
       (spark/union
         (->> reddit-docs-comments
           (spark/map
             (fn [c]
               {:reddit_name (str "t1_" (:id c))
                :reddit_doc__json (json/write-value-as-string c)})))
         (->> reddit-docs-submissions
           (spark/map
             (fn [s]
               {:reddit_name (str "t3_" (:id s))
                :reddit_doc__json (json/write-value-as-string s)}))))))})


(defn index-rdd-to-es!
  [es-url es-index-name es-index-key get-docs-rdd]
  (uspark/run-local
    (fn [sc]
      (-> sc
        (conf/set {"es.nodes" es-url
                   "es.resource" (str es-index-name "/_doc")
                   "es.mapping.id" (name es-index-key)
                   "es.input.json" "true"})))
    (fn [sc]
      (JavaEsSpark/saveJsonToEs
        ^JavaRDD
        (spark/map json/write-value-as-string
          (get-docs-rdd sc))
        (str es-index-name "/_doc")))))



(defn index-raw-content!
  [es-url es-index-name get-data-srcs]
  (index-rdd-to-es! es-url es-index-name :reddit_name
    (fn [sc]
      (->
        (mdg-run/compute dag_indexing
          (get-data-srcs sc)
          [:raw-content-es-docs])
        :raw-content-es-docs))))


(comment



  (def es-url
    (let [es-instance-domain "ip-172-31-70-82.ec2.internal"]
      (str "http://" es-instance-domain ":9200")))

  (def es-index-name "reddit-raw-content--0")

  (def esc (es/basic-client es-url))

  ;; Initializing the index
  @(es/request esc
     {:method :put :url [es-index-name]
      :body discussion-gems.indexing.elasticsearch-schema/mapping-raw-content})

  (def done
    (index-raw-content! es-url es-index-name
      (fn [sc] (get-md-sample-data-sources #_get-data-sources sc))))

  *e)