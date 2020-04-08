(ns discussion-gems.sandbox-nlp
  (:require [discussion-gems.utils.misc :as u]
            [clojure.string :as str]
            [clojure.java.javadoc :refer [javadoc]])
  (:import (java.util Properties)
           (edu.stanford.nlp.pipeline StanfordCoreNLP CoreDocument)))


;; Tutorial: https://stanfordnlp.github.io/CoreNLP/api.html
(def pipeline
  (StanfordCoreNLP.
    (u/as-java-props
      {"annotators" (str/join "," ["tokenize" "ssplit" "pos"])
       "tokenize.language" "French"
       "tokenize.keepeol" "true"})))

(javadoc pipeline)

(def my-doc
  (doto
    (CoreDocument. ;; https://nlp.stanford.edu/nlp/javadoc/javanlp/edu/stanford/nlp/pipeline/CoreDocument.html
      "- Salut, comment ça va ?
      - Bien et toi
      - Ça va, pas à se plaindre. Tu as leu le bouquin que je t'ai filé l'autre jour ?
      - J'ai commencé, pour l'instant j'accroche pas trop...")
    (->> (.annotate pipeline))))


(-> (.tokens my-doc)
  (nth 3)
  .originalText)
(.sentences my-doc)
