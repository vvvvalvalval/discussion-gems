(ns discussion-gems.indexing.dgms-search-0
  (:require [discussion-gems.utils.misc :as u]
            [discussion-gems.parsing :as parsing]
            [discussion-gems.wrappers.elasticsearch-client :as es]
            [discussion-gems.feature-engineering.submission-db :as submdb]
            [discussion-gems.feature-engineering.reddit-markdown]
            [mapdag.step :as mdg]
            [mapdag.runtime.jvm-eval]))


(def text-content-es-fields
  (let [dag
        (merge
          discussion-gems.feature-engineering.reddit-markdown/dag_reddit-markdown-features
          {:dgms_text_contents (mdg/step [:dgms_body_raw] identity)})
        compute-fields
        (mapdag.runtime.jvm-eval/compile-graph
          {:mapdag.run/input-keys #{::parsing/md-txt}
           :mapdag.run/output-keys [:dgms_text_contents

                                    :dgms_n_sentences
                                    :dgms_n_words
                                    :dgms_n_hyperlinks
                                    :dgms_n_formatting]}
          dag)]
    (fn text-content-es-fields
      [md-txt]
      (compute-fields {::parsing/md-txt md-txt}))))


(defn common-base-fields
  [reddit-thing]
  {:reddit_name (:name reddit-thing)
   :reddit_created (some-> (:created_utc reddit-thing)
                     (parsing/instant-from-reddit-utc-field)
                     (es/es-encode-instant))
   :reddit_n_ups (:ups reddit-thing)
   :reddit_n_downs (:downs reddit-thing)
   :reddit_score (:score reddit-thing)})


(defn es-doc-for-submission
  [reddit-subm]
  (u/remove-nil-vals
    (merge
      (common-base-fields reddit-subm)
      {:reddit_type "reddit_type_submission"

       :subm_reddit_name (:name reddit-subm)
       :subm_title (:title reddit-subm)
       :dgms_flair (:link_flair_text reddit-subm)}
      (text-content-es-fields
        (str (:title reddit-subm)
          (when-some [slftxt (parsing/non-empty-md-txt
                               (:selftext reddit-subm))]
            (str "\n\n"
              slftxt)))))))


(defn base-es-doc-for-comment
  [reddit-cmt]
  (merge
    (common-base-fields reddit-cmt)
    {:reddit_type "reddit_type_comment"
     :parent_reddit_name (:parent_id reddit-cmt)}
    (text-content-es-fields
      (parsing/non-empty-md-txt
        (:body reddit-cmt)))))


(defn comment-fields-from-relatives
  [subm-db c]
  (merge
    (let [subm-doc (submdb/submission subm-db)]
      (select-keys
        subm-doc
        [:subm_reddit_name
         :subm_title
         :dgms_flair]))
    (when-some [p (submdb/parent-of subm-db c)]
      {:dgms_parent_text_contents (:dgms_text_contents p)})))



(defn es-docs-for-submission-and-its-comments
  [{:as _subm-m
    reddit-subm :dgms_submdb_submission
    reddit-cmts :dgms_submdb_comments_list}]
  (let [subm-doc (es-doc-for-submission reddit-subm)
        base-cmts-docs (mapv
                         base-es-doc-for-comment
                         reddit-cmts)
        subm-db (submdb/submission-db
                  subm-doc
                  base-cmts-docs)]
    (into [subm-doc]
      (map
        (fn [c]
          (u/remove-nil-vals
            (merge c
              (comment-fields-from-relatives subm-db c)))))
      base-cmts-docs)))
