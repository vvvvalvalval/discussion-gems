(ns discussion-gems.experiments.detecting-praise-comments
  "Building a classifier for 'Praise Comments' in r/france.

  _Praise Comments_ are comments that express a judgement of high quality or helpfulness
  in reply to some content. Detecting them can lead us to Outstanding Comments, although
  clearly not all Outstanding Comments will have Praise Comments; therefore, in the retrieval
  of Outstanding Comments, Praise Comments help with precision more than with recall.

  Some examples of 'Praise Comments':

  1. 'Thank you, this is very interesting.'
  2. 'Thanks for such an informative reply.'
  3. 'Very clear explanation, I learned a lot.'
  4. 'Thanks for making such a well-articulated argument, that's interesting.'

  If a comment expresses nothing more than gratitude, it's not clear that it's a praise comment,
  so it should be labelled with a probability of 0.5."
  (:require [discussion-gems.utils.misc :as u]
            [clojure.string :as str]
            [discussion-gems.parsing.french :as parsing-fr]
            [discussion-gems.algs.word-embeddings :as wmbed]
            [clojure.java.io :as io]
            [discussion-gems.parsing :as parsing]
            [discussion-gems.algs.linalg :as ulinalg]
            [discussion-gems.data-sources :as dgds]
            [sparkling.core :as spark]
            [discussion-gems.utils.spark :as uspark]
            [discussion-gems.utils.encoding :as uenc]
            [clojure.data.fressian :as fressian]
            [sparkling.destructuring :as s-de])
  (:import (edu.stanford.nlp.pipeline StanfordCoreNLP CoreSentence CoreDocument)
           (edu.stanford.nlp.ling CoreLabel)
           (java.util.zip GZIPOutputStream)))


;; ------------------------------------------------------------------------------
;; Building a dataset to label

;; Problem: to train and evaluate a classifier, we need to make a dataset with labeled examples.
;;
;; Praise Comments suffer from class imbalance: there are very few of them amongst all comments.
;; Therefore, we use a heuristic to gather a dataset sufficiently dense in Praise Comments:
;; We find comments that have some similarity to typical Praise sentences.
;;
;; We want that similarity measure to have high recall: that's why we make use of cosine similarity
;; on word embeddings.


(def r-france-sociopolitical-flairs
  "We restrict ourselves to content marked with flairs dedicated to socio-political discussions.

  That's where we're likely to find most of the content of interest and less noise than in other discussions."
  #{"Culture"
    "Politique"
    "Science"
    "Société"
    "Écologie"
    "Économie"})

(defn is-comment-of-interest?
  [c]
  (and
    (not (-> c :body (= ["[deleted]"])))
    (contains? r-france-sociopolitical-flairs
      (-> c :dgms_comment_submission :link_flair_text))))



(def nlp-pipeline
  (delay
    (StanfordCoreNLP.
      (u/as-java-props
        {"annotators" (str/join "," ["tokenize" "ssplit"])
         "tokenize.language" "French"
         "tokenize.keepeol" "true"}))))


(defn normalize-term
  "To improve recall, we do basic normalization on terms."
  [^String t]
  (-> t
    (str/lower-case)
    (parsing-fr/normalize-french-diacritics) ;; removing accents and such, as mispellings are frequent
    (str/replace #"\W" "") ;; this rids us of "'" in words, as well as punctuation tokens.
    (as-> t
      (when-not (or
                  (= t "")
                  ;; IMPROVEMENT maybe replace this with a compiled Regex for efficiency. (Val, 29 Apr 2020)
                  (contains? parsing-fr/lucene-french-stopwords t))
        t))))


(def d_w2vec
  "We use fastText's model for vectorizing individual terms."
  (delay
    (wmbed/load-word-embedding-index
      normalize-term
      50000
      (io/file "../models/fastText_cc.fr.300.vec.txt"))))


(defn phrase-tokens
  [^CoreSentence sntc]
  (into []
    (keep
      (let [stop-words parsing-fr/lucene-french-stopwords]
        (fn [^CoreLabel tkn]
          (-> (.value tkn)
            normalize-term))))
    (.tokens sntc)))

(comment
  (->>
    (doto (CoreDocument.
            "Merci pour cette argumentation détaillée, c'est intéressant.")
      (->> (.annotate @nlp-pipeline)))
    (.sentences)
    first
    phrase-tokens)
  => ["merci" "argumentation" "detaillee" "interessant"]

  *e)


(def reference-praise-sentences
  ["Merci, très intéressant."
   "Merci pour cette réponse claire et instructive."
   "Merci pour ce commentaire clair et instructif."
   "Merci pour cette argumentation détaillée, c'est intéressant."
   "Réponse intéressante, merci."
   "Explication claire et intéressante."
   "Excellente réponse, merci."
   "Excellent commentaire, merci."
   "Merci, c'est un point de vue intéressant."])


(defn doc-vector
  "Vectorizes human text as the normalized sum of its individual term vectors."
  [body-raw]
  (let [doc (doto
              (CoreDocument. ^String body-raw)
              (->> (.annotate @nlp-pipeline)))
        sntcs (u/take-first-and-last
                ;; NOTE Praise is usually located at the very beginning or end of the praising comment, so we only vectorize the beginning and ending sentences. (Val, 29 Apr 2020)
                2 2
                (.sentences doc))
        token-vectors
        (into []
          (comp
            (mapcat phrase-tokens)
            (keep #(wmbed/token-vector @d_w2vec %)))
          sntcs)]
    (if (empty? token-vectors)
      nil
      (ulinalg/vec-project-unit
        (apply ulinalg/vec-add token-vectors)))))


(def comment-pre-sim-score
  (let [d_praise-sentence-vectors
        (delay
          (mapv doc-vector
            reference-praise-sentences))]
    (fn comment-pre-sim-score [c]
      (if-some [v (some->
                    (or
                      (:dgms_body_raw c)
                      (parsing/trim-markdown
                        {::parsing/remove-quotes true
                         ::parsing/remove-code true}
                        (:body c)))
                    (doc-vector))]
        (apply max
          (map #(ulinalg/vec-cosine-sim % v)
            @d_praise-sentence-vectors))
        -1.))))

(defn select-dataset-to-label
  [sc]
  (letfn [(rand-from-comment [c]
            (-> c :name u/draw-random-from-string))]
    (let [comments-rdd (->> (dgds/comments-all-rdd sc)
                         (spark/filter #(is-comment-of-interest? %)))
          n-comments (spark/count comments-rdd)
          high-sim-comments
          (->> comments-rdd
            (spark/key-by #(comment-pre-sim-score %))
            (spark/sort-by-key compare false)
            (spark/take 20000)
            (into []
              (map s-de/value)))
          ordinary-comments
          (->> comments-rdd
            (spark/sample false (/ 5e3 n-comments) 75701441)
            (spark/collect))]
      (vec
        (concat
          (->> high-sim-comments
            (take 5000)
            (u/draw-n-by rand-from-comment 2000))
          (->> high-sim-comments
            (drop 5000)
            (u/draw-n-by rand-from-comment 3000))
          ordinary-comments)))))


(comment ;; Saving the sampled comments to a file

  (def d_selected-comments
    (uspark/run-local
      (fn [sc]
        (select-dataset-to-label sc))))

  (with-open [wtr
              (uenc/fressian-writer
                (GZIPOutputStream.
                  (io/output-stream "../detecting-praise-comments_dataset_v0.fressian")))]
    (fressian/write-object wtr @d_selected-comments))


  *e)





