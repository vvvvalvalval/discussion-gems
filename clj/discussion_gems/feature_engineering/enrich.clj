(ns discussion-gems.feature-engineering.enrich
  (:require [discussion-gems.parsing :as parsing]
            [clojure.java.io :as io]
            [jsonista.core :as json]
            [discussion-gems.utils.spark :as uspark]
            [sparkling.conf :as conf]
            [discussion-gems.feature-engineering.comments-graph]
            [discussion-gems.data-sources :as dgds]
            [sparkling.core :as spark]))

(defn enrich-comment
  [c]
  (merge c
    (when-some [md-html-forest (parsing/md->html-forest (:body c))]
      (let [body-raw (parsing/raw-text-contents
                       {::parsing/remove-code true
                        ::parsing/remove-quotes true}
                       md-html-forest)]
        {:dgms_body_raw body-raw
         #_#_
             :dgms_body_vector
             (py/with-gil
               (float-array
                 (let [parsed (@fr_pipeline body-raw)]
                   (py.- parsed vector))))
         :dgms_syntax_stats_fr
         (parsing/syntax-stats-fr body-raw)
         :dgms_hyperlinks
         (parsing/hyperlinks md-html-forest)
         :dgms_n_formatting
         (parsing/formatting-count md-html-forest)}))))

(comment

  (-> (io/resource "reddit-france-comments-dv-sample.json")
    (json/read-value json-mpr)
    (->>
      (take 10)
      (mapv enrich-comment)))

  (-> (io/resource "reddit-france-comments-dv-sample.json")
    (json/read-value json-mpr)
    count
    (->>
      (run! enrich-comment)
      time))

  (/ 150470.826249 3172)

  ;; Saving enriched comments
  (def d_saved-enriched
    (uspark/run-local
      (fn [sc]
        (->>
          (uspark/from-hadoop-fressian-sequence-file sc "../derived-data/reddit-france/comments/RC-enriched_v1.seqfile")
          (spark/map #(enrich-comment %))
          (uspark/save-to-hadoop-fressian-seqfile
            "../derived-data/reddit-france/comments/RC-enriched_v2.seqfile")))))

  (def d_saved-enriched-2
    (uspark/run-local
      (fn [sc]
        (->>
          (uspark/from-hadoop-fressian-sequence-file sc "../derived-data/reddit-france/comments/RC-enriched_v2.seqfile")
          (discussion-gems.feature-engineering.comments-graph/enrich-comments-with-subm-data
            :dgms_comment_submission
            #(discussion-gems.feature-engineering.comments-graph/basic-submission-data %)
            (dgds/submissions-all-rdd sc))
          (uspark/save-to-hadoop-fressian-seqfile
            "../derived-data/reddit-france/comments/RC-enriched_v3.seqfile")))))

  *e)