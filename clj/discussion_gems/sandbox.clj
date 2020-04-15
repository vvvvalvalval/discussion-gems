(ns discussion-gems.sandbox
  (:require [sparkling.core :as spark]
            [manifold.deferred :as mfd]
            [sparkling.conf :as conf]
            [clj-http.client :as htc]
            [discussion-gems.utils.spark :as uspark]
            [clojure.java.shell]
            [clojure.string :as str]
            [jsonista.core :as json]
            [discussion-gems.utils.misc :as u]
            [vvvvalvalval.supdate.api :as supd]
            [clojure.pprint :as pp])
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

  *e)


(defn map-keys-features
  [m]
  (->> m
    (map (fn [[k v]]
           (str "KEY `" (name k) "` " (if v "T" "F"))))))

(defn textual-features
  [field-label txt]
  (into
    [(format "`%s`->length-h1b=%d" field-label (-> txt count Long/highestOneBit))]
    (for [[label rgx] [["WEIRD_CHARS" #"[^\w\s\p{IsLatin}\p{Punct}]"]
                       ["DIGITS" #"\d"]
                       ["URL" #"http.://"]
                       ["QUOTE" #"(?m)^\s*\&gt"]]
          :when (re-find rgx txt)]
      (format "`%s` CONTAINS %s" field-label label))))

(defn integer-features
  [field-label n]
  [(format "`%s`->h1b=%s%d"
     field-label
     (if (neg? n) "-" "+")
     (-> n long Math/abs
       Long/highestOneBit))])

(defn timestamp-features
  [field-label ts]
  (let [d (-> ts
            (cond-> (string? ts) (Long/parseLong 10))
            (* 1000) long
            java.util.Date.)]
    [(format "`%s`->Y-M=%2$tY-%2$tm"
       field-label
       d)]))

(defn comment-features
  [c]
  (into #{}
    cat
    [(map-keys-features c)
     (when-some [score (:score c)]
       (integer-features "score" score))
     (when-some [cutc (:created_utc c)]
       (timestamp-features "created_utc" cutc))
     (textual-features "body" (:body c))]))

(comment
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

  (->> sample-comments
    (mapcat comment-features)
    frequencies
    (into (sorted-map)))


  (def d_features-counts
    (uspark/run-local
      (fn [sc]
        (->> (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/comments/RC.seqfile")
          (spark/map
            (fn [^String l]
              (json/read-value l json-mpr)))
          (spark/flat-map comment-features)
          (spark/map-to-pair
            (fn [ftr]
              (spark/tuple ftr 1)))
          (spark/reduce-by-key +)
          spark/collect-map
          (into (sorted-map))))))

  @d_features-counts
  =>
  {"KEY `all_awardings` T" 862875,
   "KEY `approved_at_utc` F" 133002,
   "KEY `approved_by` F" 47206,
   "KEY `archived` F" 1727801,
   "KEY `archived` T" 191142,
   "KEY `associated_award` F" 442198,
   "KEY `author_cakeday` T" 15826,
   "KEY `author_created_utc` F" 202082,
   "KEY `author_created_utc` T" 2046176,
   "KEY `author_flair_background_color` F" 890296,
   "KEY `author_flair_background_color` T" 1500906,
   "KEY `author_flair_css_class` F" 2284736,
   "KEY `author_flair_css_class` T" 3737674,
   "KEY `author_flair_richtext` T" 2226174,
   "KEY `author_flair_template_id` F" 1969469,
   "KEY `author_flair_template_id` T" 736619,
   "KEY `author_flair_text_color` F" 889700,
   "KEY `author_flair_text_color` T" 1501502,
   "KEY `author_flair_text` F" 2284100,
   "KEY `author_flair_text` T" 3738310,
   "KEY `author_flair_type` T" 2226174,
   "KEY `author_fullname` F" 138085,
   "KEY `author_fullname` T" 2110173,
   "KEY `author_patreon_flair` F" 1709948,
   "KEY `author_premium` F" 131179,
   "KEY `author_premium` T" 5830,
   "KEY `author` T" 6022410,
   "KEY `awarders` T" 500176,
   "KEY `banned_at_utc` F" 102647,
   "KEY `banned_by` F" 47206,
   "KEY `body_html` F" 4,
   "KEY `body_html` T" 47202,
   "KEY `body` T" 6022410,
   "KEY `can_gild` F" 61053,
   "KEY `can_gild` T" 4065219,
   "KEY `can_mod_post` F" 2757181,
   "KEY `collapsed_because_crowd_control` F" 177685,
   "KEY `collapsed_reason` F" 2523933,
   "KEY `collapsed_reason` T" 60705,
   "KEY `collapsed` F" 2483075,
   "KEY `collapsed` T" 101563,
   "KEY `controversiality` T" 6022410,
   "KEY `created_utc` T" 6022410,
   "KEY `created` F" 4,
   "KEY `created` T" 551,
   "KEY `distinguished` F" 5992827,
   "KEY `distinguished` T" 29583,
   "KEY `downs` T" 545823,
   "KEY `edited` F" 5677919,
   "KEY `edited` T" 344491,
   "KEY `gilded` T" 6022410,
   "KEY `gildings` T" 2044568,
   "KEY `id` T" 6022410,
   "KEY `is_submitter` F" 3445037,
   "KEY `is_submitter` T" 182945,
   "KEY `likes` F" 47206,
   "KEY `link_id` T" 6022410,
   "KEY `locked` F" 870198,
   "KEY `locked` T" 80,
   "KEY `mod_note` F" 7581,
   "KEY `mod_reason_by` F" 7581,
   "KEY `mod_reason_title` F" 7581,
   "KEY `mod_reports` F" 4,
   "KEY `mod_reports` T" 47202,
   "KEY `name` T" 545823,
   "KEY `no_follow` F" 721669,
   "KEY `no_follow` T" 2038948,
   "KEY `num_reports` F" 47206,
   "KEY `parent_id` T" 6022410,
   "KEY `permalink` T" 3311531,
   "KEY `quarantined` F" 1107757,
   "KEY `removal_reason` F" 2970432,
   "KEY `removal_reason` T" 4,
   "KEY `replies` F" 4,
   "KEY `replies` T" 47202,
   "KEY `report_reasons` F" 47206,
   "KEY `retrieved_on` T" 6019202,
   "KEY `rte_mode` T" 124466,
   "KEY `saved` F" 47206,
   "KEY `score_hidden` F" 1050649,
   "KEY `score_hidden` T" 983,
   "KEY `score` T" 6022410,
   "KEY `send_replies` F" 10293,
   "KEY `send_replies` T" 2750324,
   "KEY `steward_reports` T" 540394,
   "KEY `stickied` F" 5280070,
   "KEY `stickied` T" 5628,
   "KEY `subreddit_id` T" 6022410,
   "KEY `subreddit_name_prefixed` T" 2266736,
   "KEY `subreddit_type` T" 3190638,
   "KEY `subreddit` T" 6022410,
   "KEY `total_awards_received` T" 862875,
   "KEY `ups` T" 1363347,
   "KEY `user_reports` F" 4,
   "KEY `user_reports` T" 47202,
   "`body` CONTAINS DIGITS" 1339396,
   "`body` CONTAINS QUOTE" 613387,
   "`body` CONTAINS URL" 312772,
   "`body` CONTAINS WEIRD_CHARS" 492233,
   "`body`->length-h1b=0" 95,
   "`body`->length-h1b=1" 3726,
   "`body`->length-h1b=1024" 149598,
   "`body`->length-h1b=128" 1257389,
   "`body`->length-h1b=16" 451003,
   "`body`->length-h1b=16384" 2,
   "`body`->length-h1b=2" 20526,
   "`body`->length-h1b=2048" 35376,
   "`body`->length-h1b=256" 871399,
   "`body`->length-h1b=32" 919013,
   "`body`->length-h1b=4" 65238,
   "`body`->length-h1b=4096" 8252,
   "`body`->length-h1b=512" 436827,
   "`body`->length-h1b=64" 1316566,
   "`body`->length-h1b=8" 486043,
   "`body`->length-h1b=8192" 1357,
   "`created_utc`->Y-M=2008-10" 14,
   "`created_utc`->Y-M=2008-11" 22,
   "`created_utc`->Y-M=2008-12" 14,
   "`created_utc`->Y-M=2008-3" 4,
   "`created_utc`->Y-M=2008-4" 9,
   "`created_utc`->Y-M=2008-5" 5,
   "`created_utc`->Y-M=2008-6" 12,
   "`created_utc`->Y-M=2008-7" 6,
   "`created_utc`->Y-M=2008-8" 6,
   "`created_utc`->Y-M=2008-9" 9,
   "`created_utc`->Y-M=2009-1" 23,
   "`created_utc`->Y-M=2009-10" 52,
   "`created_utc`->Y-M=2009-11" 19,
   "`created_utc`->Y-M=2009-12" 51,
   "`created_utc`->Y-M=2009-2" 34,
   "`created_utc`->Y-M=2009-3" 30,
   "`created_utc`->Y-M=2009-4" 21,
   "`created_utc`->Y-M=2009-5" 29,
   "`created_utc`->Y-M=2009-6" 30,
   "`created_utc`->Y-M=2009-7" 24,
   "`created_utc`->Y-M=2009-8" 13,
   "`created_utc`->Y-M=2009-9" 61,
   "`created_utc`->Y-M=2010-1" 133,
   "`created_utc`->Y-M=2010-10" 580,
   "`created_utc`->Y-M=2010-11" 447,
   "`created_utc`->Y-M=2010-12" 448,
   "`created_utc`->Y-M=2010-2" 87,
   "`created_utc`->Y-M=2010-3" 92,
   "`created_utc`->Y-M=2010-4" 123,
   "`created_utc`->Y-M=2010-5" 102,
   "`created_utc`->Y-M=2010-6" 129,
   "`created_utc`->Y-M=2010-7" 90,
   "`created_utc`->Y-M=2010-8" 139,
   "`created_utc`->Y-M=2010-9" 127,
   "`created_utc`->Y-M=2011-1" 609,
   "`created_utc`->Y-M=2011-10" 1649,
   "`created_utc`->Y-M=2011-11" 1510,
   "`created_utc`->Y-M=2011-12" 1196,
   "`created_utc`->Y-M=2011-2" 526,
   "`created_utc`->Y-M=2011-3" 593,
   "`created_utc`->Y-M=2011-4" 562,
   "`created_utc`->Y-M=2011-5" 708,
   "`created_utc`->Y-M=2011-6" 927,
   "`created_utc`->Y-M=2011-7" 974,
   "`created_utc`->Y-M=2011-8" 1103,
   "`created_utc`->Y-M=2011-9" 1029,
   "`created_utc`->Y-M=2012-1" 1892,
   "`created_utc`->Y-M=2012-10" 3822,
   "`created_utc`->Y-M=2012-11" 2988,
   "`created_utc`->Y-M=2012-12" 2181,
   "`created_utc`->Y-M=2012-2" 1813,
   "`created_utc`->Y-M=2012-3" 1991,
   "`created_utc`->Y-M=2012-4" 2728,
   "`created_utc`->Y-M=2012-5" 3357,
   "`created_utc`->Y-M=2012-6" 2353,
   "`created_utc`->Y-M=2012-7" 2888,
   "`created_utc`->Y-M=2012-8" 2806,
   "`created_utc`->Y-M=2012-9" 3138,
   "`created_utc`->Y-M=2013-1" 3370,
   "`created_utc`->Y-M=2013-10" 5833,
   "`created_utc`->Y-M=2013-11" 5985,
   "`created_utc`->Y-M=2013-12" 6536,
   "`created_utc`->Y-M=2013-2" 2645,
   "`created_utc`->Y-M=2013-3" 3949,
   "`created_utc`->Y-M=2013-4" 5792,
   "`created_utc`->Y-M=2013-5" 4569,
   "`created_utc`->Y-M=2013-6" 5503,
   "`created_utc`->Y-M=2013-7" 5185,
   "`created_utc`->Y-M=2013-8" 5051,
   "`created_utc`->Y-M=2013-9" 4831,
   "`created_utc`->Y-M=2014-1" 7656,
   "`created_utc`->Y-M=2014-10" 18405,
   "`created_utc`->Y-M=2014-11" 19738,
   "`created_utc`->Y-M=2014-12" 23277,
   "`created_utc`->Y-M=2014-2" 5270,
   "`created_utc`->Y-M=2014-3" 7008,
   "`created_utc`->Y-M=2014-4" 9055,
   "`created_utc`->Y-M=2014-5" 10182,
   "`created_utc`->Y-M=2014-6" 10436,
   "`created_utc`->Y-M=2014-7" 13156,
   "`created_utc`->Y-M=2014-8" 12756,
   "`created_utc`->Y-M=2014-9" 14592,
   "`created_utc`->Y-M=2015-1" 31254,
   "`created_utc`->Y-M=2015-10" 45944,
   "`created_utc`->Y-M=2015-11" 57432,
   "`created_utc`->Y-M=2015-12" 52658,
   "`created_utc`->Y-M=2015-2" 31260,
   "`created_utc`->Y-M=2015-3" 38834,
   "`created_utc`->Y-M=2015-4" 45191,
   "`created_utc`->Y-M=2015-5" 43479,
   "`created_utc`->Y-M=2015-6" 50455,
   "`created_utc`->Y-M=2015-7" 48242,
   "`created_utc`->Y-M=2015-8" 41702,
   "`created_utc`->Y-M=2015-9" 46017,
   "`created_utc`->Y-M=2016-1" 58670,
   "`created_utc`->Y-M=2016-10" 65670,
   "`created_utc`->Y-M=2016-11" 85843,
   "`created_utc`->Y-M=2016-12" 81868,
   "`created_utc`->Y-M=2016-2" 56486,
   "`created_utc`->Y-M=2016-3" 60291,
   "`created_utc`->Y-M=2016-4" 60852,
   "`created_utc`->Y-M=2016-5" 63535,
   "`created_utc`->Y-M=2016-6" 63941,
   "`created_utc`->Y-M=2016-7" 66165,
   "`created_utc`->Y-M=2016-8" 74001,
   "`created_utc`->Y-M=2016-9" 69830,
   "`created_utc`->Y-M=2017-1" 95548,
   "`created_utc`->Y-M=2017-10" 120893,
   "`created_utc`->Y-M=2017-11" 111509,
   "`created_utc`->Y-M=2017-12" 101574,
   "`created_utc`->Y-M=2017-2" 92468,
   "`created_utc`->Y-M=2017-3" 111394,
   "`created_utc`->Y-M=2017-4" 170739,
   "`created_utc`->Y-M=2017-5" 177632,
   "`created_utc`->Y-M=2017-6" 136131,
   "`created_utc`->Y-M=2017-7" 116416,
   "`created_utc`->Y-M=2017-8" 103927,
   "`created_utc`->Y-M=2017-9" 109896,
   "`created_utc`->Y-M=2018-1" 114899,
   "`created_utc`->Y-M=2018-10" 298595,
   "`created_utc`->Y-M=2018-11" 135375,
   "`created_utc`->Y-M=2018-12" 158641,
   "`created_utc`->Y-M=2018-2" 102039,
   "`created_utc`->Y-M=2018-3" 120045,
   "`created_utc`->Y-M=2018-4" 125580,
   "`created_utc`->Y-M=2018-5" 124852,
   "`created_utc`->Y-M=2018-6" 123404,
   "`created_utc`->Y-M=2018-7" 131973,
   "`created_utc`->Y-M=2018-8" 139716,
   "`created_utc`->Y-M=2018-9" 134984,
   "`created_utc`->Y-M=2019-1" 159695,
   "`created_utc`->Y-M=2019-2" 141355,
   "`created_utc`->Y-M=2019-3" 142289,
   "`created_utc`->Y-M=2019-4" 141449,
   "`created_utc`->Y-M=2019-5" 142270,
   "`created_utc`->Y-M=2019-6" 135547,
   "`created_utc`->Y-M=2019-7" 155973,
   "`created_utc`->Y-M=2019-8" 129641,
   "`created_utc`->Y-M=2019-9" 119233,
   "`score`->h1b=+0" 240893,
   "`score`->h1b=+1" 1897869,
   "`score`->h1b=+1024" 23,
   "`score`->h1b=+128" 6682,
   "`score`->h1b=+16" 257398,
   "`score`->h1b=+2" 1670924,
   "`score`->h1b=+2048" 3,
   "`score`->h1b=+256" 1216,
   "`score`->h1b=+32" 92865,
   "`score`->h1b=+4" 947611,
   "`score`->h1b=+4096" 1,
   "`score`->h1b=+512" 203,
   "`score`->h1b=+64" 27627,
   "`score`->h1b=+8" 567033,
   "`score`->h1b=-1" 92415,
   "`score`->h1b=-128" 58,
   "`score`->h1b=-16" 17655,
   "`score`->h1b=-2" 81470,
   "`score`->h1b=-256" 6,
   "`score`->h1b=-32" 4495,
   "`score`->h1b=-4" 72205,
   "`score`->h1b=-512" 1,
   "`score`->h1b=-64" 685,
   "`score`->h1b=-8" 43072}


  (def d_comments-dv-sample
    (uspark/run-local
      (fn [sc]
        (->> (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/comments/RC.seqfile")
          (spark/map
            (fn [^String l]
              (json/read-value l json-mpr)))
          (uspark/diversified-sample sc 20
            #(u/draw-random-from-string (:id %))
            comment-features)
          spark/collect shuffle vec))))

  (count @d_comments-dv-sample)
  => 3172

  (take 10 @d_comments-dv-sample)

  (spit "../reddit-france-comments-dv-sample.json"
    (json/write-value-as-string
      @d_comments-dv-sample
      (json/object-mapper
        {:encode-key-fn true
         :pretty true})))

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

  *e)


(defn submission-features
  [s]
  (into #{}
    cat
    [(map-keys-features s)
     (when-some [lft (:link_flair_text s)]
       [(str "`link_flair_text`=" lft)])
     (when-some [lfty (:link_flair_type s)]
       [(str "`link_flair_type`=" lfty)])
     (when-some [title (:title s)]
       (textual-features "title" title))
     (when-some [st (:selftext s)]
       (textual-features "selftext" st))
     (when-some [cutc (:created_utc s)]
       (timestamp-features "created_utc" cutc))
     (for [field [:score :ups :downs :num_comments :num_crossposts :num_reports]
           :let [v (get s field)]
           :when (some? v)
           ftr (integer-features (name field) v)]
       ftr)]))



(comment

  (def d_submissions-rd-sample
    (uspark/run-local
      (fn [sc]
        (->>
          (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/submissions/RS.seqfile")
          (spark/sample false (/ 1e2 255581) 3408366)
          (spark/map
            (fn [^String l]
              (json/read-value l json-mpr)))
          (spark/collect)
          (mapv #(into (sorted-map) %))
          shuffle vec))))


  (take 10 @d_submissions-rd-sample)

  (->> @d_submissions-rd-sample
    (mapcat submission-features)
    frequencies
    (into (sorted-map)))


  (def d_features-counts
    (uspark/run-local
      (fn [sc]
        (->> (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/submissions/RS.seqfile")
          (spark/map
            (fn [^String l]
              (json/read-value l json-mpr)))
          (spark/flat-map submission-features)
          (spark/map-to-pair
            (fn [ftr]
              (spark/tuple ftr 1)))
          (spark/reduce-by-key +)
          spark/collect-map
          (into (sorted-map))))))

  @d_features-counts
  =>
  {"KEY `all_awardings` T" 31443,
   "KEY `allow_live_comments` F" 14855,
   "KEY `allow_live_comments` T" 280,
   "KEY `approved_at_utc` F" 13912,
   "KEY `approved_by` F" 14344,
   "KEY `archived` F" 227170,
   "KEY `archived` T" 14916,
   "KEY `author_cakeday` T" 568,
   "KEY `author_created_utc` F" 14615,
   "KEY `author_created_utc` T" 60980,
   "KEY `author_flair_background_color` F" 45598,
   "KEY `author_flair_background_color` T" 41573,
   "KEY `author_flair_css_class` F" 156286,
   "KEY `author_flair_css_class` T" 99295,
   "KEY `author_flair_richtext` T" 71710,
   "KEY `author_flair_template_id` F" 72527,
   "KEY `author_flair_template_id` T" 14644,
   "KEY `author_flair_text_color` F" 45595,
   "KEY `author_flair_text_color` T" 41576,
   "KEY `author_flair_text` F" 156271,
   "KEY `author_flair_text` T" 99310,
   "KEY `author_flair_type` T" 71710,
   "KEY `author_fullname` F" 11377,
   "KEY `author_fullname` T" 64218,
   "KEY `author_patreon_flair` F" 53075,
   "KEY `author` T" 255581,
   "KEY `banned_at_utc` F" 13912,
   "KEY `banned_by` F" 21859,
   "KEY `brand_safe` T" 81724,
   "KEY `can_gild` F" 16865,
   "KEY `can_gild` T" 81891,
   "KEY `can_mod_post` F" 92218,
   "KEY `category` F" 87171,
   "KEY `clicked` F" 14344,
   "KEY `collections` T" 4,
   "KEY `content_categories` F" 87171,
   "KEY `contest_mode` F" 183211,
   "KEY `contest_mode` T" 13,
   "KEY `created_utc` T" 255581,
   "KEY `created` T" 47353,
   "KEY `crosspost_parent_list` T" 3198,
   "KEY `crosspost_parent` T" 3198,
   "KEY `discussion_type` F" 15135,
   "KEY `distinguished` F" 253127,
   "KEY `distinguished` T" 2454,
   "KEY `domain` T" 255581,
   "KEY `downs` T" 95551,
   "KEY `edited` F" 248343,
   "KEY `edited` T" 7238,
   "KEY `event_end` T" 2,
   "KEY `event_is_live` F" 2,
   "KEY `event_start` T" 2,
   "KEY `from_id` F" 59363,
   "KEY `from_kind` F" 59363,
   "KEY `from` F" 59363,
   "KEY `gilded` T" 250102,
   "KEY `gildings` T" 64452,
   "KEY `hidden` F" 174374,
   "KEY `hide_score` F" 178058,
   "KEY `hide_score` T" 77,
   "KEY `id` T" 255581,
   "KEY `is_crosspostable` F" 74560,
   "KEY `is_crosspostable` T" 53024,
   "KEY `is_meta` F" 78306,
   "KEY `is_original_content` F" 87018,
   "KEY `is_original_content` T" 153,
   "KEY `is_reddit_media_domain` F" 113262,
   "KEY `is_reddit_media_domain` T" 10339,
   "KEY `is_robot_indexable` F" 20738,
   "KEY `is_robot_indexable` T" 43714,
   "KEY `is_self` F" 207931,
   "KEY `is_self` T" 47650,
   "KEY `is_video` F" 147457,
   "KEY `is_video` T" 518,
   "KEY `likes` F" 14344,
   "KEY `link_flair_background_color` T" 84176,
   "KEY `link_flair_css_class` F" 96116,
   "KEY `link_flair_css_class` T" 159465,
   "KEY `link_flair_richtext` T" 87171,
   "KEY `link_flair_template_id` F" 19168,
   "KEY `link_flair_template_id` T" 47250,
   "KEY `link_flair_text_color` T" 87171,
   "KEY `link_flair_text` F" 96116,
   "KEY `link_flair_text` T" 159465,
   "KEY `link_flair_type` T" 87171,
   "KEY `locked` F" 215072,
   "KEY `locked` T" 1824,
   "KEY `media_embed` T" 255581,
   "KEY `media_metadata` F" 448,
   "KEY `media_metadata` T" 140,
   "KEY `media_only` F" 87171,
   "KEY `media` F" 222780,
   "KEY `media` T" 32801,
   "KEY `mod_note` F" 3965,
   "KEY `mod_reason_by` F" 3965,
   "KEY `mod_reason_title` F" 3965,
   "KEY `mod_reports` T" 16380,
   "KEY `name` T" 88036,
   "KEY `no_follow` F" 49926,
   "KEY `no_follow` T" 46150,
   "KEY `num_comments` T" 255581,
   "KEY `num_crossposts` T" 127584,
   "KEY `num_reports` F" 14344,
   "KEY `over_18` F" 254539,
   "KEY `over_18` T" 1042,
   "KEY `parent_whitelist_status` T" 127584,
   "KEY `permalink` T" 255581,
   "KEY `pinned` F" 123601,
   "KEY `post_categories` F" 8865,
   "KEY `post_hint` T" 129071,
   "KEY `preview` T" 129071,
   "KEY `previous_visits` T" 204,
   "KEY `pwls` T" 87171,
   "KEY `quarantine` F" 193309,
   "KEY `removal_reason` F" 87169,
   "KEY `removal_reason` T" 2,
   "KEY `report_reasons` F" 16380,
   "KEY `retrieved_on` T" 250102,
   "KEY `rte_mode` T" 8865,
   "KEY `saved` F" 88036,
   "KEY `score` T" 255581,
   "KEY `secure_media_embed` T" 250102,
   "KEY `secure_media` F" 218937,
   "KEY `secure_media` T" 31165,
   "KEY `selftext_html` F" 16796,
   "KEY `selftext_html` T" 5063,
   "KEY `selftext` T" 255581,
   "KEY `send_replies` F" 9983,
   "KEY `send_replies` T" 86093,
   "KEY `spoiler` F" 179672,
   "KEY `spoiler` T" 281,
   "KEY `stickied` F" 250092,
   "KEY `stickied` T" 10,
   "KEY `subreddit_id` T" 255581,
   "KEY `subreddit_name_prefixed` T" 78306,
   "KEY `subreddit_subscribers` T" 91869,
   "KEY `subreddit_type` T" 114637,
   "KEY `subreddit` T" 255581,
   "KEY `suggested_sort` F" 168171,
   "KEY `suggested_sort` T" 724,
   "KEY `thumbnail_height` F" 36522,
   "KEY `thumbnail_height` T" 118883,
   "KEY `thumbnail_width` F" 36522,
   "KEY `thumbnail_width` T" 118883,
   "KEY `thumbnail` T" 255581,
   "KEY `title` T" 255581,
   "KEY `total_awards_received` T" 31443,
   "KEY `ups` T" 95551,
   "KEY `url` T" 255581,
   "KEY `user_reports` T" 16380,
   "KEY `view_count` F" 27821,
   "KEY `visited` F" 8865,
   "KEY `whitelist_status` T" 127584,
   "KEY `wls` T" 87171,
   "`created_utc`->Y-M=2011-01" 4,
   "`created_utc`->Y-M=2011-02" 207,
   "`created_utc`->Y-M=2011-03" 278,
   "`created_utc`->Y-M=2011-04" 191,
   "`created_utc`->Y-M=2011-05" 232,
   "`created_utc`->Y-M=2011-06" 215,
   "`created_utc`->Y-M=2011-07" 175,
   "`created_utc`->Y-M=2011-08" 220,
   "`created_utc`->Y-M=2011-09" 226,
   "`created_utc`->Y-M=2011-10" 249,
   "`created_utc`->Y-M=2011-11" 288,
   "`created_utc`->Y-M=2011-12" 248,
   "`created_utc`->Y-M=2012-01" 393,
   "`created_utc`->Y-M=2012-02" 351,
   "`created_utc`->Y-M=2012-03" 337,
   "`created_utc`->Y-M=2012-04" 411,
   "`created_utc`->Y-M=2012-05" 438,
   "`created_utc`->Y-M=2012-06" 350,
   "`created_utc`->Y-M=2012-07" 330,
   "`created_utc`->Y-M=2012-08" 336,
   "`created_utc`->Y-M=2012-09" 361,
   "`created_utc`->Y-M=2012-10" 365,
   "`created_utc`->Y-M=2012-11" 348,
   "`created_utc`->Y-M=2012-12" 366,
   "`created_utc`->Y-M=2013-01" 456,
   "`created_utc`->Y-M=2013-02" 375,
   "`created_utc`->Y-M=2013-03" 425,
   "`created_utc`->Y-M=2013-04" 618,
   "`created_utc`->Y-M=2013-05" 461,
   "`created_utc`->Y-M=2013-06" 538,
   "`created_utc`->Y-M=2013-07" 497,
   "`created_utc`->Y-M=2013-08" 365,
   "`created_utc`->Y-M=2013-09" 490,
   "`created_utc`->Y-M=2013-10" 553,
   "`created_utc`->Y-M=2013-11" 633,
   "`created_utc`->Y-M=2013-12" 664,
   "`created_utc`->Y-M=2014-01" 741,
   "`created_utc`->Y-M=2014-02" 549,
   "`created_utc`->Y-M=2014-03" 727,
   "`created_utc`->Y-M=2014-04" 740,
   "`created_utc`->Y-M=2014-05" 840,
   "`created_utc`->Y-M=2014-06" 744,
   "`created_utc`->Y-M=2014-07" 866,
   "`created_utc`->Y-M=2014-08" 885,
   "`created_utc`->Y-M=2014-09" 995,
   "`created_utc`->Y-M=2014-10" 1254,
   "`created_utc`->Y-M=2014-11" 1276,
   "`created_utc`->Y-M=2014-12" 1201,
   "`created_utc`->Y-M=2015-01" 2513,
   "`created_utc`->Y-M=2015-02" 1512,
   "`created_utc`->Y-M=2015-03" 1694,
   "`created_utc`->Y-M=2015-04" 1724,
   "`created_utc`->Y-M=2015-05" 1789,
   "`created_utc`->Y-M=2015-06" 2031,
   "`created_utc`->Y-M=2015-07" 1934,
   "`created_utc`->Y-M=2015-08" 1676,
   "`created_utc`->Y-M=2015-09" 1924,
   "`created_utc`->Y-M=2015-10" 2051,
   "`created_utc`->Y-M=2015-11" 3343,
   "`created_utc`->Y-M=2015-12" 2303,
   "`created_utc`->Y-M=2016-01" 2599,
   "`created_utc`->Y-M=2016-02" 2998,
   "`created_utc`->Y-M=2016-03" 2838,
   "`created_utc`->Y-M=2016-04" 2766,
   "`created_utc`->Y-M=2016-05" 3320,
   "`created_utc`->Y-M=2016-06" 3633,
   "`created_utc`->Y-M=2016-07" 3049,
   "`created_utc`->Y-M=2016-08" 2848,
   "`created_utc`->Y-M=2016-09" 3271,
   "`created_utc`->Y-M=2016-10" 3345,
   "`created_utc`->Y-M=2016-11" 3683,
   "`created_utc`->Y-M=2016-12" 4030,
   "`created_utc`->Y-M=2017-01" 3939,
   "`created_utc`->Y-M=2017-02" 4499,
   "`created_utc`->Y-M=2017-03" 5043,
   "`created_utc`->Y-M=2017-04" 6896,
   "`created_utc`->Y-M=2017-05" 7022,
   "`created_utc`->Y-M=2017-06" 5022,
   "`created_utc`->Y-M=2017-07" 8890,
   "`created_utc`->Y-M=2017-08" 3983,
   "`created_utc`->Y-M=2017-09" 4475,
   "`created_utc`->Y-M=2017-10" 4489,
   "`created_utc`->Y-M=2017-11" 9434,
   "`created_utc`->Y-M=2017-12" 4349,
   "`created_utc`->Y-M=2018-01" 4778,
   "`created_utc`->Y-M=2018-02" 4207,
   "`created_utc`->Y-M=2018-03" 4698,
   "`created_utc`->Y-M=2018-04" 4436,
   "`created_utc`->Y-M=2018-05" 4429,
   "`created_utc`->Y-M=2018-06" 4493,
   "`created_utc`->Y-M=2018-07" 5097,
   "`created_utc`->Y-M=2018-08" 4264,
   "`created_utc`->Y-M=2018-09" 4442,
   "`created_utc`->Y-M=2018-10" 4789,
   "`created_utc`->Y-M=2018-11" 5326,
   "`created_utc`->Y-M=2018-12" 6446,
   "`created_utc`->Y-M=2019-01" 6353,
   "`created_utc`->Y-M=2019-02" 5653,
   "`created_utc`->Y-M=2019-03" 5677,
   "`created_utc`->Y-M=2019-04" 5534,
   "`created_utc`->Y-M=2019-05" 5097,
   "`created_utc`->Y-M=2019-06" 4960,
   "`created_utc`->Y-M=2019-07" 5547,
   "`created_utc`->Y-M=2019-08" 4628,
   "`downs`->h1b=+0" 90925,
   "`downs`->h1b=+1" 762,
   "`downs`->h1b=+16" 157,
   "`downs`->h1b=+2" 1486,
   "`downs`->h1b=+32" 15,
   "`downs`->h1b=+4" 1525,
   "`downs`->h1b=+64" 2,
   "`downs`->h1b=+8" 679,
   "`link_flair_text`= Gwenn ha du" 2,
   "`link_flair_text`=2011" 1,
   "`link_flair_text`=2015" 2,
   "`link_flair_text`=2017" 1,
   "`link_flair_text`=2018" 1,
   "`link_flair_text`=2019" 1,
   "`link_flair_text`=8==϶" 2,
   "`link_flair_text`=AMA" 335,
   "`link_flair_text`=AMA ?" 1,
   "`link_flair_text`=Abruti" 1,
   "`link_flair_text`=Actus" 14967,
   "`link_flair_text`=Aide / Help" 6950,
   "`link_flair_text`=Article de 2018" 1,
   "`link_flair_text`=Ask France" 5499,
   "`link_flair_text`=Ask France " 2008,
   "`link_flair_text`=BOLOS" 1,
   "`link_flair_text`=BZH" 1,
   "`link_flair_text`=Baguette" 1,
   "`link_flair_text`=Baguette ?" 1,
   "`link_flair_text`=Banane" 1,
   "`link_flair_text`=Bolos" 8,
   "`link_flair_text`=Boloss" 16,
   "`link_flair_text`=Branlette" 1,
   "`link_flair_text`=Bretagne" 2,
   "`link_flair_text`=Béta" 1,
   "`link_flair_text`=COQ" 11,
   "`link_flair_text`=COmiQ" 1,
   "`link_flair_text`=Caca" 1,
   "`link_flair_text`=Caca poteau" 1,
   "`link_flair_text`=Cacapoteau" 1,
   "`link_flair_text`=Compost :coq:" 1,
   "`link_flair_text`=Compteur" 972,
   "`link_flair_text`=Coq" 2,
   "`link_flair_text`=Crédit Agricole " 1,
   "`link_flair_text`=Cuisine" 1,
   "`link_flair_text`=Culture" 19271,
   "`link_flair_text`=Culture ✏️ 🔨" 1,
   "`link_flair_text`=Céta" 1,
   "`link_flair_text`=DDP" 1,
   "`link_flair_text`=Debat" 2,
   "`link_flair_text`=Divers / Misc" 1,
   "`link_flair_text`=Doublon" 1,
   "`link_flair_text`=Découverte" 1,
   "`link_flair_text`=Départements" 10,
   "`link_flair_text`=Economie" 1,
   "`link_flair_text`=FAUSSE NOUVELLE" 1,
   "`link_flair_text`=Forum Libre" 4827,
   "`link_flair_text`=Forum Libre :superdupont:" 5,
   "`link_flair_text`=Forum Libre 🥖" 1,
   "`link_flair_text`=Gagnant" 1,
   "`link_flair_text`=Humour" 13339,
   "`link_flair_text`=Humour 2013" 1,
   "`link_flair_text`=IMPORTANT" 1,
   "`link_flair_text`=INTOX" 3,
   "`link_flair_text`=Intox" 23,
   "`link_flair_text`=Jan 2018" 1,
   "`link_flair_text`=K.K. Krieg" 143,
   "`link_flair_text`=Lapsus" 1,
   "`link_flair_text`=Love Story" 1,
   "`link_flair_text`=Low conten" 705,
   "`link_flair_text`=Low effort" 1067,
   "`link_flair_text`=Malaise" 1,
   "`link_flair_text`=Meta" 954,
   "`link_flair_text`=Moderation" 1,
   "`link_flair_text`=Modération" 42,
   "`link_flair_text`=Méta" 1983,
   "`link_flair_text`=Méta :nsfw-hover:" 2,
   "`link_flair_text`=Métal" 1,
   "`link_flair_text`=News" 9242,
   "`link_flair_text`=Non sécurisé" 8,
   "`link_flair_text`=Nov 2018" 1,
   "`link_flair_text`=Nov. 2017" 1,
   "`link_flair_text`=OP1-Boloss 0" 1,
   "`link_flair_text`=OP=Con" 1,
   "`link_flair_text`=Oh là là" 1,
   "`link_flair_text`=Orange" 1,
   "`link_flair_text`=Paywall" 2226,
   "`link_flair_text`=Perfidie" 1,
   "`link_flair_text`=PetitPain" 1,
   "`link_flair_text`=Politique" 29695,
   "`link_flair_text`=Politique (2010)" 1,
   "`link_flair_text`=Politique - Septembre 2018" 1,
   "`link_flair_text`=Politique 2012" 1,
   "`link_flair_text`=Publicité" 1,
   "`link_flair_text`=Péage" 15,
   "`link_flair_text`=Péage de lecture numérique" 1,
   "`link_flair_text`=Retrouvé" 1,
   "`link_flair_text`=Santé" 8,
   "`link_flair_text`=Science" 4452,
   "`link_flair_text`=Sciences" 26,
   "`link_flair_text`=Sel" 19,
   "`link_flair_text`=Société" 27748,
   "`link_flair_text`=Soft Paywall" 1,
   "`link_flair_text`=Soft paywall" 52,
   "`link_flair_text`=Sondage" 1,
   "`link_flair_text`=Sport" 3042,
   "`link_flair_text`=Super Héros" 1,
   "`link_flair_text`=Techno" 1,
   "`link_flair_text`=Technologie" 2,
   "`link_flair_text`=Technos" 7382,
   "`link_flair_text`=Trompeur" 2,
   "`link_flair_text`=Trompeur " 5,
   "`link_flair_text`=Trompeur !" 1,
   "`link_flair_text`=Trompeur ?" 169,
   "`link_flair_text`=edit: piste criminelle exclue" 1,
   "`link_flair_text`=« Culture »" 1,
   "`link_flair_text`=Éco - Compteur" 1,
   "`link_flair_text`=Écologie" 1181,
   "`link_flair_text`=Économie" 976,
   "`link_flair_text`=★ Méta ★" 1,
   "`link_flair_text`=🇫🇷" 1,
   "`link_flair_type`=richtext" 50794,
   "`link_flair_type`=text" 36377,
   "`num_comments`->h1b=+0" 55190,
   "`num_comments`->h1b=+1" 26826,
   "`num_comments`->h1b=+1024" 255,
   "`num_comments`->h1b=+128" 4990,
   "`num_comments`->h1b=+16" 29872,
   "`num_comments`->h1b=+2" 31352,
   "`num_comments`->h1b=+2048" 6,
   "`num_comments`->h1b=+256" 1685,
   "`num_comments`->h1b=+32" 20252,
   "`num_comments`->h1b=+4" 36016,
   "`num_comments`->h1b=+4096" 3,
   "`num_comments`->h1b=+512" 882,
   "`num_comments`->h1b=+64" 11330,
   "`num_comments`->h1b=+8" 36921,
   "`num_comments`->h1b=-1" 1,
   "`num_crossposts`->h1b=+0" 125505,
   "`num_crossposts`->h1b=+1" 1764,
   "`num_crossposts`->h1b=+2" 263,
   "`num_crossposts`->h1b=+4" 42,
   "`num_crossposts`->h1b=+8" 10,
   "`score`->h1b=+0" 60217,
   "`score`->h1b=+1" 41218,
   "`score`->h1b=+1024" 497,
   "`score`->h1b=+128" 5040,
   "`score`->h1b=+16" 25293,
   "`score`->h1b=+16384" 3,
   "`score`->h1b=+2" 24920,
   "`score`->h1b=+2048" 75,
   "`score`->h1b=+256" 2064,
   "`score`->h1b=+32" 16839,
   "`score`->h1b=+32768" 2,
   "`score`->h1b=+4" 34516,
   "`score`->h1b=+4096" 22,
   "`score`->h1b=+512" 1066,
   "`score`->h1b=+64" 10013,
   "`score`->h1b=+8" 33792,
   "`score`->h1b=+8192" 4,
   "`selftext` CONTAINS DIGITS" 19284,
   "`selftext` CONTAINS QUOTE" 652,
   "`selftext` CONTAINS URL" 5020,
   "`selftext` CONTAINS WEIRD_CHARS" 6079,
   "`selftext`->length-h1b=0" 188036,
   "`selftext`->length-h1b=1" 29,
   "`selftext`->length-h1b=1024" 4361,
   "`selftext`->length-h1b=128" 5239,
   "`selftext`->length-h1b=16" 532,
   "`selftext`->length-h1b=16384" 44,
   "`selftext`->length-h1b=2" 25,
   "`selftext`->length-h1b=2048" 1725,
   "`selftext`->length-h1b=256" 10444,
   "`selftext`->length-h1b=32" 1076,
   "`selftext`->length-h1b=32768" 5,
   "`selftext`->length-h1b=4" 90,
   "`selftext`->length-h1b=4096" 631,
   "`selftext`->length-h1b=512" 8031,
   "`selftext`->length-h1b=64" 2303,
   "`selftext`->length-h1b=8" 32815,
   "`selftext`->length-h1b=8192" 195,
   "`title` CONTAINS DIGITS" 49136,
   "`title` CONTAINS QUOTE" 9,
   "`title` CONTAINS URL" 299,
   "`title` CONTAINS WEIRD_CHARS" 41066,
   "`title`->length-h1b=1" 26,
   "`title`->length-h1b=128" 12909,
   "`title`->length-h1b=16" 29743,
   "`title`->length-h1b=2" 214,
   "`title`->length-h1b=256" 2006,
   "`title`->length-h1b=32" 99630,
   "`title`->length-h1b=4" 983,
   "`title`->length-h1b=64" 104820,
   "`title`->length-h1b=8" 5250,
   "`ups`->h1b=+0" 21398,
   "`ups`->h1b=+1" 17064,
   "`ups`->h1b=+1024" 31,
   "`ups`->h1b=+128" 972,
   "`ups`->h1b=+16" 9305,
   "`ups`->h1b=+2" 10742,
   "`ups`->h1b=+2048" 6,
   "`ups`->h1b=+256" 309,
   "`ups`->h1b=+32" 5002,
   "`ups`->h1b=+4" 14697,
   "`ups`->h1b=+4096" 2,
   "`ups`->h1b=+512" 95,
   "`ups`->h1b=+64" 2354,
   "`ups`->h1b=+8" 13574}


  (def d_submissions-dv-sample
    (uspark/run-local
      (fn [sc]
        (->> (uspark/from-hadoop-text-sequence-file sc "../datasets/reddit-france/submissions/RS.seqfile")
          (spark/map
            (fn [^String l]
              (json/read-value l json-mpr)))
          (uspark/diversified-sample sc 20
            #(u/draw-random-from-string (:id %))
            submission-features)
          spark/collect shuffle
          (mapv #(into (sorted-map) %))))))

  (count @d_submissions-dv-sample)
  => 3333


  (take 10 @d_submissions-dv-sample)

  (spit "../reddit-france-submissions-dv-sample.json"
    (json/write-value-as-string
      @d_submissions-dv-sample
      (json/object-mapper
        {:encode-key-fn true
         :pretty true})))

  *e)


(comment ;; Interesting flairs

  (->> ["Culture"
        "Politique"
        "Science"
        "Société"
        "Écologie"
        "Économie"]
    (map #(str "`link_flair_text`=" %))
    (map
      {"`link_flair_text`= Gwenn ha du" 2,
       "`link_flair_text`=2011" 1,
       "`link_flair_text`=2015" 2,
       "`link_flair_text`=2017" 1,
       "`link_flair_text`=2018" 1,
       "`link_flair_text`=2019" 1,
       "`link_flair_text`=8==϶" 2,
       "`link_flair_text`=AMA" 335,
       "`link_flair_text`=AMA ?" 1,
       "`link_flair_text`=Abruti" 1,
       "`link_flair_text`=Actus" 14967,
       "`link_flair_text`=Aide / Help" 6950,
       "`link_flair_text`=Article de 2018" 1,
       "`link_flair_text`=Ask France" 5499,
       "`link_flair_text`=Ask France " 2008,
       "`link_flair_text`=BOLOS" 1,
       "`link_flair_text`=BZH" 1,
       "`link_flair_text`=Baguette" 1,
       "`link_flair_text`=Baguette ?" 1,
       "`link_flair_text`=Banane" 1,
       "`link_flair_text`=Bolos" 8,
       "`link_flair_text`=Boloss" 16,
       "`link_flair_text`=Branlette" 1,
       "`link_flair_text`=Bretagne" 2,
       "`link_flair_text`=Béta" 1,
       "`link_flair_text`=COQ" 11,
       "`link_flair_text`=COmiQ" 1,
       "`link_flair_text`=Caca" 1,
       "`link_flair_text`=Caca poteau" 1,
       "`link_flair_text`=Cacapoteau" 1,
       "`link_flair_text`=Compost :coq:" 1,
       "`link_flair_text`=Compteur" 972,
       "`link_flair_text`=Coq" 2,
       "`link_flair_text`=Crédit Agricole " 1,
       "`link_flair_text`=Cuisine" 1,
       "`link_flair_text`=Culture" 19271,
       "`link_flair_text`=Culture ✏️ 🔨" 1,
       "`link_flair_text`=Céta" 1,
       "`link_flair_text`=DDP" 1,
       "`link_flair_text`=Debat" 2,
       "`link_flair_text`=Divers / Misc" 1,
       "`link_flair_text`=Doublon" 1,
       "`link_flair_text`=Découverte" 1,
       "`link_flair_text`=Départements" 10,
       "`link_flair_text`=Economie" 1,
       "`link_flair_text`=FAUSSE NOUVELLE" 1,
       "`link_flair_text`=Forum Libre" 4827,
       "`link_flair_text`=Forum Libre :superdupont:" 5,
       "`link_flair_text`=Forum Libre 🥖" 1,
       "`link_flair_text`=Gagnant" 1,
       "`link_flair_text`=Humour" 13339,
       "`link_flair_text`=Humour 2013" 1,
       "`link_flair_text`=IMPORTANT" 1,
       "`link_flair_text`=INTOX" 3,
       "`link_flair_text`=Intox" 23,
       "`link_flair_text`=Jan 2018" 1,
       "`link_flair_text`=K.K. Krieg" 143,
       "`link_flair_text`=Lapsus" 1,
       "`link_flair_text`=Love Story" 1,
       "`link_flair_text`=Low conten" 705,
       "`link_flair_text`=Low effort" 1067,
       "`link_flair_text`=Malaise" 1,
       "`link_flair_text`=Meta" 954,
       "`link_flair_text`=Moderation" 1,
       "`link_flair_text`=Modération" 42,
       "`link_flair_text`=Méta" 1983,
       "`link_flair_text`=Méta :nsfw-hover:" 2,
       "`link_flair_text`=Métal" 1,
       "`link_flair_text`=News" 9242,
       "`link_flair_text`=Non sécurisé" 8,
       "`link_flair_text`=Nov 2018" 1,
       "`link_flair_text`=Nov. 2017" 1,
       "`link_flair_text`=OP1-Boloss 0" 1,
       "`link_flair_text`=OP=Con" 1,
       "`link_flair_text`=Oh là là" 1,
       "`link_flair_text`=Orange" 1,
       "`link_flair_text`=Paywall" 2226,
       "`link_flair_text`=Perfidie" 1,
       "`link_flair_text`=PetitPain" 1,
       "`link_flair_text`=Politique" 29695,
       "`link_flair_text`=Politique (2010)" 1,
       "`link_flair_text`=Politique - Septembre 2018" 1,
       "`link_flair_text`=Politique 2012" 1,
       "`link_flair_text`=Publicité" 1,
       "`link_flair_text`=Péage" 15,
       "`link_flair_text`=Péage de lecture numérique" 1,
       "`link_flair_text`=Retrouvé" 1,
       "`link_flair_text`=Santé" 8,
       "`link_flair_text`=Science" 4452,
       "`link_flair_text`=Sciences" 26,
       "`link_flair_text`=Sel" 19,
       "`link_flair_text`=Société" 27748,
       "`link_flair_text`=Soft Paywall" 1,
       "`link_flair_text`=Soft paywall" 52,
       "`link_flair_text`=Sondage" 1,
       "`link_flair_text`=Sport" 3042,
       "`link_flair_text`=Super Héros" 1,
       "`link_flair_text`=Techno" 1,
       "`link_flair_text`=Technologie" 2,
       "`link_flair_text`=Technos" 7382,
       "`link_flair_text`=Trompeur" 2,
       "`link_flair_text`=Trompeur " 5,
       "`link_flair_text`=Trompeur !" 1,
       "`link_flair_text`=Trompeur ?" 169,
       "`link_flair_text`=edit: piste criminelle exclue" 1,
       "`link_flair_text`=« Culture »" 1,
       "`link_flair_text`=Éco - Compteur" 1,
       "`link_flair_text`=Écologie" 1181,
       "`link_flair_text`=Économie" 976,
       "`link_flair_text`=★ Méta ★" 1,
       "`link_flair_text`=🇫🇷" 1,
       "`link_flair_type`=richtext" 50794,
       "`link_flair_type`=text" 36377})
    (apply +))
  => 83323

  *e)

(comment ;; some collection-size statistics

  (Long/highestOneBit 127) => 64

  (def body-length-buckets
    (->
      ["`body`->length-h1b=0" 95,
       "`body`->length-h1b=1" 3726,
       "`body`->length-h1b=1024" 149598,
       "`body`->length-h1b=128" 1257389,
       "`body`->length-h1b=16" 451003,
       "`body`->length-h1b=16384" 2,
       "`body`->length-h1b=2" 20526,
       "`body`->length-h1b=2048" 35376,
       "`body`->length-h1b=256" 871399,
       "`body`->length-h1b=32" 919013,
       "`body`->length-h1b=4" 65238,
       "`body`->length-h1b=4096" 8252,
       "`body`->length-h1b=512" 436827,
       "`body`->length-h1b=64" 1316566,
       "`body`->length-h1b=8" 486043,
       "`body`->length-h1b=8192" 1357,]
      (->>
        (partition 2)
        (mapv
          (let [pl (count "`body`->length-h1b=")]
            (fn [[k v]]
              {:body-length-h1b (Long/parseLong
                                  (subs k pl)
                                  10)
               :n v})))
        (sort-by :body-length-h1b u/decreasing)
        (reductions
          (fn [{cum-n :cum-n cum-l :cum-length} {bl :body-length-h1b n :n, :as row}]
            (assoc row
              :cum-n (+ cum-n n)
              :cum-length (+ cum-l (* n bl))))
          {:cum-n 0 :cum-length 0.})
        rest
        vec)))

  body-length-buckets

  (pp/print-table body-length-buckets)

  ;| :body-length-h1b |      :n |  :cum-n |   :cum-length |
  ;|------------------+---------+---------+---------------|
  ;|            16384 |       2 |       2 |       32768.0 |
  ;|             8192 |    1357 |    1359 |   1.1149312E7 |
  ;|             4096 |    8252 |    9611 |   4.4949504E7 |
  ;|             2048 |   35376 |   44987 |  1.17399552E8 |
  ;|             1024 |  149598 |  194585 |  2.70587904E8 |
  ;|              512 |  436827 |  631412 |  4.94243328E8 |  <-- :cum-n: # comments with 512 or more characters in body
  ;|              256 |  871399 | 1502811 |  7.17321472E8 |
  ;|              128 | 1257389 | 2760200 |  8.78267264E8 |
  ;|               64 | 1316566 | 4076766 |  9.62527488E8 |
  ;|               32 |  919013 | 4995779 |  9.91935904E8 |
  ;|               16 |  451003 | 5446782 |  9.99151952E8 |
  ;|                8 |  486043 | 5932825 | 1.003040296E9 |
  ;|                4 |   65238 | 5998063 | 1.003301248E9 |
  ;|                2 |   20526 | 6018589 |   1.0033423E9 |
  ;|                1 |    3726 | 6022315 | 1.003346026E9 |
  ;|                0 |      95 | 6022410 | 1.003346026E9 |

  ;; Interpretation: comments with >= 512 characters account of about 10% of comments in cardinality,
  ;; but around 50% of character weight


  ;; # of comments in interesting flairs ?



  *e)
