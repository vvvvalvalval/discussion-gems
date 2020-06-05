(ns discussion-gems.data-sources
  (:require [discussion-gems.utils.spark :as uspark]
            [discussion-gems.parsing :as parsing]
            [discussion-gems.utils.encoding :as uenc]
            [sparkling.core :as spark]))

(defn comments-all-rdd
  [sc]
  (->>
    (uspark/from-hadoop-fressian-sequence-file sc "../derived-data/reddit-france/comments/RC-enriched_v3.seqfile")
    (spark/map #(parsing/backfill-reddit-name "t1_" %))))

(defn submissions-all-rdd
  [sc]
  (->>
    (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/submissions/RS.seqfile")
    (spark/map uenc/json-read)
    (spark/map #(parsing/backfill-reddit-name "t3_" %))))


;; ------------------------------------------------------------------------------
;; Moderate-sized samples (2% of content, so about 4k posts and 120k comments)

(def seqfile-comments-md
  "../derived-data/reddit-france/comments/RC-enriched_v3_sample1e-2.seqfile")

(def seqfile-subm-md
  "../derived-data/reddit-france/submissions/RS_sample1e-2.seqfile")

(comment ;; Modelerate-sized samples

  (def d_saved-comments-sample
    (uspark/run-local
      (fn [sc]
        (->> (comments-all-rdd sc)
          (uspark/sample-rdd-by-key :link_id 2e-2)
          (uspark/save-to-hadoop-fressian-seqfile seqfile-comments-md)))))

  (def d_saved-subm-sample
    (uspark/run-local
      (fn [sc]
        (->> (submissions-all-rdd sc)
          (uspark/sample-rdd-by-key :name 2e-2)
          (uspark/save-to-hadoop-fressian-seqfile seqfile-subm-md)))))

  *e)

(defn comments-md-sample-rdd
  [sc]
  (->>
    (uspark/from-hadoop-fressian-sequence-file sc seqfile-comments-md)
    (spark/map #(parsing/backfill-reddit-name "t1_" %))))

(defn subm-md-sample-rdd
  [sc]
  (->>
    (uspark/from-hadoop-fressian-sequence-file sc seqfile-subm-md)
    (spark/map #(parsing/backfill-reddit-name "t3_" %))))