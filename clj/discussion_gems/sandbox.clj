(ns discussion-gems.sandbox
  (:require [sparkling.core :as spark]
            [manifold.deferred :as mfd]
            [sparkling.conf :as conf]
            [clj-http.client :as htc]
            [discussion-gems.utils.spark :as uspark]
            [clojure.java.shell]
            [clojure.string :as str]
            [jsonista.core :as json]
            [discussion-gems.utils.misc :as u])
  #_(:import (org.apache.hadoop.io.compress ZStandardCodec)))

(require 'sc.api)
;; Reading the raw source files

(comment

  (def comment-files
    (->
      (htc/get "https://files.pushshift.io/reddit/comments/")
      :body
      (->>
        (re-seq #">((RC_\d{4}\-\d{2})\.(\w+))</a>")
        (mapv
          (fn [[_ fname base-name ext]]
            [fname base-name ext])))))


  (run! println
    (->> [["zst" "zstd -cdq"]
          ["bz2" "bzip2 -d"]
          ["xz" "unxz"]]
      (mapv
        (fn [[fext decompress-cmd]]
          (str
            "(trap \"\" HUP; "
            "wget -q -O - "
            (->> comment-files
              (filter
                (fn [[fname base-name ext]]
                  (= ext fext)))
              (map
                (fn [[fname base-name ext]]
                  (str "https://files.pushshift.io/reddit/comments/" fname)))
              (str/join " "))
            " | " decompress-cmd
            " | grep '\"france\"' | jq -c 'select (.subreddit == \"france\")' | gzip -c"
            ") >> datasets/reddit-france/comments/RC-" fext ".jsonl.gz"
            " < /dev/null 2>./download-err.txt &")))))

  22510
  22516
  22522

  (def submissions-files
    (->
      (htc/get "https://files.pushshift.io/reddit/submissions/")
      :body
      (->>
        (re-seq #">((RS_\d{4}\-\d{2})\.(\w+))</a>")
        (mapv
          (fn [[_ fname base-name ext]]
            [fname base-name ext])))))

  (run! println
    (->> [#_["zst" "zstd -cdq"] ;; FIXME
          ["bz2" "bzip2 -d"]
          #_["xz" "unxz"]]
      (mapcat
        (fn [[fext decompress-cmd]]
          (->> submissions-files
            (filter
              (fn [[fname base-name ext]]
                (= ext fext)))
            (reverse)
            (map-indexed
              (fn [i t]
                [i t]))
            (u/group-and-map-by
              (fn [[i _t]] (mod i 6))
              (fn [[_i t]] t))
            (mapv
              (fn [[b ts]]
                (str
                  "(trap \"\" HUP; "
                  "wget -q -O - "
                  (->> ts
                    (map
                      (fn [[fname base-name ext]]
                        (str "https://files.pushshift.io/reddit/submissions/" fname)))
                    (str/join " "))
                  " | " decompress-cmd
                  " | grep '\"france\"' | jq -c 'select (.subreddit == \"france\")' | gzip -c"
                  ") >> datasets/reddit-france/submissions/RS-" fext "_" b ".jsonl.gz"
                  " < /dev/null 2>>./download-err.txt &"))))))))

  22852
  22858
  22859

  26065
  26071
  26111





  *e)


(comment

  @(uspark/run-local
     (fn [sc]
       (->>
         (spark/text-file sc "deps.edn")
         (spark/take 3))))

  @(mfd/future
     (spark/with-context
       sc (-> (conf/spark-conf)
            (conf/master "local[*]")
            (conf/app-name "discussion-gems-local")
            (conf/set {"spark.driver.allowMultipleContexts" "true"})
            (conf/set {"spark.driver.allowMultipleContexts" "true"}))
       (->>
         (spark/text-file sc "../pushshift/reddit/comments/RC_2019-08.zst")
         (spark/take 3))))


  *e)

(def json-mpr
  (json/object-mapper
    {:decode-key-fn true}))


(comment ;; Exploring downloaded comments from r/france

  ;;;; Merging all jsonl.gz into one (temporary) jsonl file
  ;ubuntu@ip-172-31-40-156:~/discussion-gems$ (trap "" HUP; cat ../datasets/reddit-france/comments/*.jsonl.gz | gunzip) >> ../datasets/reddit-france/comments/RC.jsonl  < /dev/null 2>>../copy-err.txt &
  ;; Took less than a minute

  ;ubuntu@ip-172-31-40-156:~/discussion-gems$ wc -l ../datasets/reddit-france/comments/RC.jsonl
  ;6022410
  ;; So that seems complete

  ;; Let's save this to a Hadoop SequenceFile
  (def done
    (uspark/run-local
      (fn [sc]
        (->>
          (spark/text-file sc "../datasets/reddit-france/comments/RC.jsonl")
          (spark/map-to-pair
            (fn [l] (spark/tuple "" l)))
          (uspark/save-to-hadoop-text+text-seqfile "../datasets/reddit-france/comments/RC.seqfile")))))



  ;; How many comments? Can we JSON-decode them all? How long does it take ?
  (time
    @(uspark/run-local
       (fn [sc]
         (->>
           (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/comments/RC.seqfile")
           (spark/map (fn [^String l] (json/read-value l json-mpr)))
           (spark/count)))))
  ; "Elapsed time: 64287.270686 msecs"
  => 6022410
  (/ 64287.270686 6022410) => 0.010674675202452175 ;; 10µs/comment



  ;; What does comments metadata look like ?
  (def sample-comments
    @(uspark/run-local
       (fn [sc]
         (->>
           (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/comments/RC.seqfile")
           (spark/sample false (/ 1e2 6022410) 824402)
           (spark/map
             (fn [^String l]
               (json/read-value l json-mpr)))
           (spark/collect)
           (mapv #(into (sorted-map) %))
           shuffle vec))))

  ;; TODO diversified sampling of comments (Val, 08 Apr 2020)

  *e)


(defn comment-features
  [c]
  (into #{}
    cat
    [(->> c
       (map (fn [[k v]]
              (str "KEY " (name k) " " (if v "T" "F")))))
     [(str "body->length-h1b=" (-> c :body count Long/highestOneBit))
      (str "score->h1b="
        (if (neg? (:score c)) "-" "+")
        (-> c :score long Math/abs
          Long/highestOneBit))]
     (when-some [cutc (:created_utc c)]
       (let [d (-> cutc
                 (cond-> (string? cutc) (Long/parseLong 10))
                 (* 1000) java.util.Date.)]
         [(str "created_utc->Y-M="
            (-> d .getYear (+ 1900))
            "-"
            (-> d .getMonth (+ 1)))]))
     (->> c :body set
       (map (fn [chr]
              (str "body HAS_CHAR " chr))))]))

(comment
  (->> sample-comments
    (mapcat comment-features)
    frequencies
    (into (sorted-map)))

  *e)


(comment  ;; Exploring downloaded submissions from r/france

  ;;;; Merging all jsonl.gz into one (temporary) jsonl file
  ;ubuntu@ip-172-31-40-156:~/discussion-gems$ (trap "" HUP; cat ../datasets/reddit-france/submissions/*.jsonl.gz | gunzip) >> ../datasets/reddit-france/submissions/RS.jsonl  < /dev/null 2>>../copy-err.txt &
  ;; Took a few seconds

  ;ubuntu@ip-172-31-40-156:~/discussion-gems$ wc -l ../datasets/reddit-france/submissions/RS.jsonl
  ;255581

  ;; Let's save this to a Hadoop SequenceFile
  (def done
    (uspark/run-local
      (fn [sc]
        (->>
          (spark/text-file sc "../datasets/reddit-france/submissions/RS.jsonl")
          (spark/map-to-pair
            (fn [l] (spark/tuple "" l)))
          (uspark/save-to-hadoop-text+text-seqfile "../datasets/reddit-france/submissions/RS.seqfile")))))



  ;; How many submissions? Can we JSON-decode them all?
  (time
    @(uspark/run-local
       (fn [sc]
         (->>
           (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/submissions/RS.seqfile")
           (spark/map (fn [^String l] (json/read-value l json-mpr)))
           (spark/count)))))
  ;"Elapsed time: 6518.625515 msecs"
  => 255581
  (/ 6518.625515 255581) => 0.025505125635317177 ;; 25µs/submission

  ;; TODO diversified sampling of submissions (Val, 08 Apr 2020)

  *e)
