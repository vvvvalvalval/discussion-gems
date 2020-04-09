(ns discussion-gems.sandbox.praise-comments
  (:require [discussion-gems.config.libpython]
            [libpython-clj.require :refer [require-python]]
            [libpython-clj.python :refer [py. py.. py.-] :as py]
            [jsonista.core :as json]
            [clojure.java.io :as io]
            [markdown.core]
            [crouton.html]
            [discussion-gems.utils.misc :as u]
            [clojure.string :as str]
            [clojure.repl :refer :all]
            [sparkling.core :as spark]
            [discussion-gems.utils.spark :as uspark])
  (:import (edu.stanford.nlp.pipeline StanfordCoreNLP CoreDocument CoreSentence)))

(require 'sc.api)

(defn trim-markdown
  [^String txt]
  (if (= txt "[deleted]")
    nil
    (let [sb (StringBuilder.)]
      (letfn
        [(aux [node]
           (cond
             (string? node)
             (.append sb ^String node)

             (map? node)
             (case (:tag node)
               (:img)
               (do nil)

               (:p :blockquote :h1 :h2 :h3 :h4 :h5 :h6)
               (do
                 (run! aux (:content node))
                 (.append sb "\n\n"))

               (:a :i :em :strong)
               (do
                 (run! aux (:content node))
                 (.append sb " "))

               (run! aux (:content node)))))]
        (let [html-forest
              (get-in
                (crouton.html/parse-string
                  (markdown.core/md-to-html-string txt))
                [:content 1 :content])]
          (run! aux html-forest)))
      (.toString sb))))


(def ssplit-pipeline
  (StanfordCoreNLP.
    (u/as-java-props
      {"annotators" (str/join "," ["tokenize" "ssplit"])
       "tokenize.language" "French"
       "tokenize.keepeol" "true"})))

(defn sentences
  [^String txt-trimmed]
  (->>
    (.sentences
      (doto
        (CoreDocument.                                      ;; https://nlp.stanford.edu/nlp/javadoc/javanlp/edu/stanford/nlp/pipeline/CoreDocument.html
          txt-trimmed)
        (->> (.annotate ssplit-pipeline))))
    (mapv
      (fn [^CoreSentence sntc]
        (.text sntc)))))


(comment
  (def comment-bodies
    (-> (slurp (io/resource "reddit-france-comments-dv-sample.json"))
      (json/read-value
        (json/object-mapper
          {:decode-key-fn true}))
      (->> (mapv :body))))

  (->> comment-bodies
    (mapv trim-markdown)
    count time)
  ;"Elapsed time: 5536.942 msecs"
  => 3172

  (->> comment-bodies
    (take 100)
    (mapv trim-markdown))

  (-> comment-bodies
    (->>
      (keep trim-markdown)
      (mapv sentences))
    count time)
  ;"Elapsed time: 6237.501 msecs"
  => 2786
  *e)


(require-python '[numpy])
(require-python '[sklearn.metrics.pairwise])
(require-python '[sentence_transformers])
(require-python '[sentence_transformers.models])

(def sentence-embedding-model
  (let [cmb (sentence_transformers.models/CamemBERT "camembert-base")
        pooling-model
        (sentence_transformers.models/Pooling
          (py. cmb get_word_embedding_dimension)
          :pooling_mode_mean_tokens true,
          :pooling_mode_cls_token false,
          :pooling_mode_max_tokens false)]
    (sentence_transformers/SentenceTransformer :modules [cmb pooling-model])))

(defn doc-sentence-similarity
  [base-sentence-encoded comment-body]
  (time
    (let [comment-sentences (-> comment-body trim-markdown sentences)]
      (double
        (numpy/average
          (sklearn.metrics.pairwise/cosine_similarity
            (py. sentence-embedding-model encode comment-sentences)
            base-sentence-encoded))))))

(def json-mpr
  (json/object-mapper
    {:decode-key-fn true}))

(comment

  (def base-sentence "Merci pour ta réponse, j'ai appris plein de choses.")

  (time
    (dotimes [_ 10]
      (doc-sentence-similarity
        base-sentence
        (str/join "\n\n"
          (repeat 5
            "Salut, ça va, tu vas bien ?

            On se présente - mais non tu nous connaît.

            On est là pour te pomper, t'imposer sans répit et sans repos, pour te sucer ton flouze, ton oseil, ton pognon, to pez, ton fric, ton blé...")))))


  @(uspark/run-local
     (fn [sc]
       (->>
         (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/comments/RC.seqfile")
         (spark/map
           (fn [^String l]
             (json/read-value l json-mpr)))
         (spark/filter
           (fn [c]
             (not
               (or
                 (-> c :body (= "[deleted]"))
                 (-> c :body count (> 500))))))
         spark/count)))
  => 5106480

  (def d_sample
    (uspark/run-local
      (fn [sc]
        (->>
          (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/comments/RC.seqfile")
          (spark/map
            (fn [^String l]
              (json/read-value l json-mpr)))
          (spark/filter
            (fn [c]
              (not
                (or
                  (-> c :body (= "[deleted]"))
                  (-> c :body count (> 500))))))
          (spark/sample false (/ 1e3 6e6) 2309892)
          spark/collect
          shuffle vec))))

  (count @d_sample)
  => 863

  (->> @d_sample
    (take 100)
    (map
      (let [bse (py. sentence-embedding-model encode [base-sentence])]
        (fn [c]
          (spark/tuple
            (doc-sentence-similarity bse (:body c))
            [(:permalink c)
             (:body c)]))))
    count time)
  ;"Elapsed time: 11576.703259 msecs"
  => 100
  (/ 11576.703259 100) => 115.76703259 ;; ms/comment


  (/
    (* 5106480 115.76703259)
    (* 1000 60 60))
  => 164.21167682782865 ;; ouch, 164 hours at best

  (def d_top-similar-comments
    (uspark/run-local
      (fn [sc]
        (->>
          (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/comments/RC.seqfile")
          (spark/map
            (fn [^String l]
              (json/read-value l json-mpr)))
          (spark/filter
            (fn [c]
              (not
                (or
                  (-> c :body (= "[deleted]"))
                  (-> c :body count (> 500))))))
          (spark/map-to-pair
            (fn [c]
              (spark/tuple
                (doc-sentence-similarity base-sentence (:body c))
                [(:permalink c)
                 (:body c)])))
          (spark/sort-by-key compare false)
          (spark/take 1000)))))

  *e)
