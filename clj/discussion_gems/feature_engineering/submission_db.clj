(ns discussion-gems.feature-engineering.submission-db
  "An in-memory data structure for navigating the comments tree of a submission"
  (:require [discussion-gems.feature-engineering.protocols]
            [discussion-gems.utils.misc :as u])
  (:import (java.util ArrayList)))

;; ------------------------------------------------------------------------------
;; Query API

;;;; Core query ops
(defn submission
  [subm-db]
  (discussion-gems.feature-engineering.protocols/submdb-submission subm-db))

(defn comments
  [subm-db]
  (discussion-gems.feature-engineering.protocols/submdb-comments subm-db))

(defn thing-by-reddit-name
  [subm-db rdt-name]
  (discussion-gems.feature-engineering.protocols/submdb-thing-by-reddit-name subm-db rdt-name))

(defn children-by-rdt-name
  [subm-db rdt-name]
  (discussion-gems.feature-engineering.protocols/submdb-children-by-reddit-name subm-db rdt-name))


;;;; Derived helpers
(defn parent-of
  [subm-db thing]
  (thing-by-reddit-name subm-db (:parent_reddit_name thing)))

(defn children-of
  [subm-db thing]
  (children-by-rdt-name subm-db (:reddit_name thing)))

;; ------------------------------------------------------------------------------
;; Implementation

;; NOTE using a Clojure deftype and protocol for lookup performance. (Val, 29 May 2020)
(deftype SubmissionDb
  [dgms_submdb_submission
   dgms_submdb_comments_list
   rdt-name->thing
   rdt-name->children-rdt-names]
  discussion-gems.feature-engineering.protocols/ISubmissionDb
  (submdb-submission [_this] dgms_submdb_submission)
  (submdb-comments [_this] dgms_submdb_comments_list)
  (submdb-thing-by-reddit-name [_this rdt-name]
    (get rdt-name->thing rdt-name))
  (submdb-children-by-reddit-name [_this rdt-name]
    (map discussion-gems.feature-engineering.protocols/submdb-thing-by-reddit-name
      (get rdt-name->children-rdt-names rdt-name nil))))

(defn submission-db
  [subm cmts]
  (->SubmissionDb
    subm
    (vec cmts)
    (persistent!
      (reduce
        (fn [tm c]
          (let [k (:reddit_name c)
                v c]
            (assoc! tm k v)))
        (transient {(:reddit_name subm) subm})
        cmts))
    (persistent!
      (reduce
        (fn [tm c]
          (let [parent-id (:parent_reddit_name c)]
            (if-some [^ArrayList l (get tm parent-id)]
              (do
                (.add l (:reddit_name c))
                tm)
              (let [l (ArrayList. 2)]
                (.add l (:reddit_name c))
                (assoc! tm :parent_reddit_name l)))))
        (transient {})
        cmts))))

