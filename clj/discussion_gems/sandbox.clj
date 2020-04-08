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



  ;;;; Merging all jsonl.gz into one jsonl file
  ;ubuntu@ip-172-31-40-156:~/discussion-gems$ (trap "" HUP; cat ../datasets/reddit-france/comments/*.jsonl.gz | gunzip) >> ../datasets/reddit-france/comments/RC.jsonl  < /dev/null 2>>../copy-err.txt &
  ;; Took less than a minute

  ;ubuntu@ip-172-31-40-156:~/discussion-gems$ wc -l ../datasets/reddit-france/comments/RC.jsonl
  ;6022410
  ;; So that seems complete

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
  ;; How many comments? Can we JSON-decode them all?
  (time
    @(uspark/run-local
       (fn [sc]
         (->>
           (spark/text-file sc "../RC-zst.jsonl")
           (spark/map (fn [^String l] (json/read-value l json-mpr)))
           (spark/count)))))
  => 1725327


  ;; Let's save this to a Hadoop SequenceFile
  (def done
    (uspark/run-local
      (fn [sc]
        (->>
          (spark/text-file sc "../RC-zst.jsonl")
          (spark/map-to-pair
            (fn [l] (spark/tuple "" l)))
          (uspark/save-to-hadoop-text+text-seqfile "../RC-zst.seqfile")))))


  ;; How long to process the whole SequenceFile?
  (time
    @(uspark/run-local
       (fn [sc]
         (->>
           (uspark/from-hadoop-text-sequence-file sc "../RC-zst.seqfile")
           (spark/map (fn [^String l] (json/read-value l json-mpr)))
           (spark/count)))))
  ;"Elapsed time: 19998.331 msecs"
  => 1725327
  (/ 19998.331 1725327) ; => 11.6 µs/doc


  (require 'sc.api)

  ;; What does comments metadata look like ?
  (def sample-comments
    @(uspark/run-local
       (fn [sc]
         (->>
           (uspark/from-hadoop-text-sequence-file sc "../RC-zst.seqfile")
           (spark/sample false (/ 1e2 1725327) 824402)
           (spark/map
             (fn [^String l]
               (json/read-value l json-mpr)))
           (spark/collect)
           (mapv #(into (sorted-map) %))
           shuffle vec))))

  *e)


(comment  ;; Exploring downloaded submissions from r/france
  ;; How many submissions? Can we JSON-decode them all?
  (time
    @(uspark/run-local
       (fn [sc]
         (->>
           (spark/text-file sc "../RS-zst.jsonl")
           (spark/map (fn [^String l] (json/read-value l json-mpr)))
           (spark/count)))))
  => 118095


  ;; Let's save this to a Hadoop SequenceFile
  (def done
    (uspark/run-local
      (fn [sc]
        (->>
          (spark/text-file sc "../RS-zst.jsonl")
          (spark/map-to-pair
            (fn [l] (spark/tuple "" l)))
          (uspark/save-to-hadoop-text+text-seqfile "../RS-zst.seqfile")))))


  ;; How long to process the whole SequenceFile?
  (time
    @(uspark/run-local
       (fn [sc]
         (->>
           (uspark/from-hadoop-text-sequence-file sc "../RS-zst.seqfile")
           (spark/map (fn [^String l] (json/read-value l json-mpr)))
           (spark/count)))))
  ;"Elapsed time: 2854.046 msecs"
  => 118095
  (/ 2854.046 118095) ; => 24.2 µs/doc


  ;; What does comments metadata look like ?
  (def sample-submissions
    @(uspark/run-local
            (fn [sc]
              (->>
                (uspark/from-hadoop-text-sequence-file sc "../RS-zst.seqfile")
                (spark/sample false (/ 1e2 118095) 450855)
                (spark/map
                  (fn [^String l]
                    (json/read-value l json-mpr)))
                (spark/collect)
                (mapv #(into (sorted-map) %))
                shuffle vec))))

  ;;;; Merging all jsonl.gz into one jsonl file
  ;ubuntu@ip-172-31-40-156:~/discussion-gems$ (trap "" HUP; cat ../datasets/reddit-france/submissions/*.jsonl.gz | gunzip) >> ../datasets/reddit-france/submissions/RS.jsonl  < /dev/null 2>>../copy-err.txt &
  ;; Took a few seconds

  ;ubuntu@ip-172-31-40-156:~/discussion-gems$ wc -l ../datasets/reddit-france/submissions/RS.jsonl
  ;255581

  *e)
