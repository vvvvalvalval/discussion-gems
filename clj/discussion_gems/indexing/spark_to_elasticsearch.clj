(ns discussion-gems.indexing.spark-to-elasticsearch
  (:require [mapdag.step :as mdg]
            [sparkling.core :as spark]
            [jsonista.core :as json]
            [mapdag.runtime.default :as mdg-run]
            [manifold.deferred :as mfd]
            [discussion-gems.utils.spark :as uspark]
            [sparkling.conf :as conf]
            [discussion-gems.data-sources :as dgds]
            [discussion-gems.feature-engineering.enrich]
            [discussion-gems.indexing.elasticsearch-schema]
            [discussion-gems.wrappers.elasticsearch-client :as es]
            [sparkling.destructuring :as s-de]
            [discussion-gems.feature-engineering.submission-db :as submdb]
            [discussion-gems.indexing.dgms-search-0]
            [discussion-gems.parsing :as parsing]
            [discussion-gems.utils.misc :as u])
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
                :reddit_doc__json (json/write-value-as-string s)}))))))

   :submissions-and-their-comments-maps
   (mdg/step [:reddit-docs-submissions :reddit-docs-comments]
     (fn [reddit-docs-submissions reddit-docs-comments]
       (let [SUBMISSION 0
             COMMENT 1]
         (->> (spark/union
                (->>
                  reddit-docs-submissions
                  (spark/map-to-pair
                    (fn [{:as s, subm_name :name}]
                      (spark/tuple subm_name
                        (spark/tuple SUBMISSION s)))))
                (->> reddit-docs-comments
                  (spark/filter :link_id)
                  (spark/map-to-pair
                    (fn [{:as c, subm_name :link_id}]
                      (spark/tuple subm_name
                        (spark/tuple COMMENT c))))))
           (spark/group-by-key)
           (spark/values)
           (spark/map
             (fn [s-and-c-tuples]
               ;; NOTE if a submission is not present in the dataset, its comments are discarded. (Val, 30 May 2020)
               (when-some [s (->> s-and-c-tuples
                               (keep
                                 (fn [t]
                                   (when (-> t s-de/key (= SUBMISSION))
                                     (s-de/value t))))
                               first)]
                 (let [cmts (into []
                              (keep
                                (fn [t]
                                  (when (-> t s-de/key (= COMMENT))
                                    (s-de/value t))))
                              s-and-c-tuples)]
                   ;; IMPROVEMENT factor out indexing
                   {:dgms_submdb_submission s
                    :dgms_submdb_comments_list cmts}))))))))

   :dgms-search-es-docs
   (mdg/step [:submissions-and-their-comments-maps]
     (fn [submissions-and-their-comments-maps]
       (->> submissions-and-their-comments-maps
         (spark/flat-map #(discussion-gems.indexing.dgms-search-0/es-docs-for-submission-and-its-comments %)))))})



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

  (def done
    (index-raw-content! es-url es-index-name
      (fn [sc] (get-data-sources sc))))

  @done

  ;; Initializing the index
  @(es/request esc
     {:method :put :url [es-index-name]
      :body discussion-gems.indexing.elasticsearch-schema/es-mapping_dgms-raw-content})


  (:body
    @(es/request esc
       {:method :post :url [es-index-name :_search]
        :body
        {:size 10
         :track_total_hits true}}))
  =>
  {:took 2,
   :timed_out false,
   :_shards {:total 1, :successful 1, :skipped 0, :failed 0},
   :hits {:total {:value 6101322, :relation "eq"}, :max_score nil, :hits []}}

  *e)