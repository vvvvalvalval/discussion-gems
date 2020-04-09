(ns discussion-gems.sandbox.praise-comments
  (:require [jsonista.core :as json]
            [clojure.java.io :as io]
            [markdown.core]
            [crouton.html]
            [discussion-gems.utils.misc :as u]
            [clojure.string :as str])
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


