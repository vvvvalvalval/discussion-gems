(ns discussion-gems.test.indexing.dgms-search-0-test
  (:require [clojure.test :as test :refer :all]
            [discussion-gems.indexing.dgms-search-0]
            [clojure.java.io :as io]
            [discussion-gems.utils.encoding :as uenc]
            [discussion-gems.utils.misc :as u]
            [discussion-gems.parsing :as parsing]
            [discussion-gems.indexing.elasticsearch-schema]))

(def get-dv-sample-submissions
  (memoize
    (fn []
      (-> (io/resource "reddit-france-submissions-dv-sample.json")
        uenc/json-read
        (->> (map #(parsing/backfill-reddit-name "t3_" %)))))))

(def get-dv-sample-comments
  (memoize
    (fn []
      (-> (io/resource "reddit-france-comments-dv-sample.json")
        uenc/json-read
        (->> (map #(parsing/backfill-reddit-name "t1_" %)))))))

(defn rewire-to-random-comments-tree
  "Given a submissions and a list of comments,
  rewrites :parent_id and :link_id of the comments
  so as to form a (random) comments tree."
  [{:as _subm-m
    reddit-subm :dgms_submdb_submission
    reddit-comments :dgms_submdb_comments_list}]
  (let [s+cs (into [reddit-subm]
               (shuffle reddit-comments))
        subm-name (:name reddit-subm)]
    {:dgms_submdb_submission reddit-subm
     :dgms_submdb_comments_list
     (into []
       (map-indexed
         (fn [i c]
           (assoc c
             :link_id subm-name
             :parent_id (:name
                          (nth s+cs
                            (rand-int i))))))
       (rest s+cs))}))



(deftest validate-es-documents-from-dv-sample
  (let [n-comments-for-ith-subm [10 100 0 1 2 5 50 200]
        subm-and-their-comments
        (loop [n-cmts-seq n-comments-for-ith-subm
               subs (get-dv-sample-submissions)
               cmts (get-dv-sample-comments)
               ret []]
          (if (empty? n-cmts-seq)
            ret
            (let [[n-cmts & rest-n-cmts] n-cmts-seq]
              (recur
                rest-n-cmts
                (rest subs)
                (drop n-cmts cmts)
                (conj ret
                  {:dgms_submdb_submission (first subs)
                   :dgms_submdb_comments_list
                   (vec (take n-cmts cmts))})))))]
    (testing "Doesn't throw on random comment tress from diversied sample, and all keys are represented"
      (is
        (=
          (-> discussion-gems.indexing.elasticsearch-schema/es-mapping_dgms-search-0
            :mappings :properties
            (keys)
            (set))
          (->> subm-and-their-comments
            (map rewire-to-random-comments-tree)
            (mapcat
              (fn [subm-m]
                (discussion-gems.indexing.dgms-search-0/es-docs-for-submission-and-its-comments subm-m)))
            (mapcat keys)
            (set)))))))

(comment

  *e)


(deftest all-base-comments-fields-are-represented
  (is
    (=
      #{:dgms_n_formatting
        :dgms_n_hyperlinks
        :dgms_n_sentences
        :dgms_n_words
        :dgms_text_contents
        :parent_reddit_name
        :reddit_created
        :reddit_n_downs
        :reddit_n_ups
        :reddit_name
        :reddit_score
        :reddit_type}
      (->> (get-dv-sample-comments)
        (take 500) ;; for performance
        (map discussion-gems.indexing.dgms-search-0/base-es-doc-for-comment)
        (map u/remove-nil-vals)
        (mapcat keys)
        (into (sorted-set))))))