(ns discussion-gems.algs.word-embeddings
  (:require [discussion-gems.algs.protocols]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.util Map List HashMap ArrayList)))

(defn word-embedding-dim
  [wei]
  (discussion-gems.algs.protocols/word-embedding-dim wei))

(defn token-vector
  [wei token]
  (discussion-gems.algs.protocols/token-vector wei token))

(defn all-word-vectors
  [wei]
  (discussion-gems.algs.protocols/all-word-vectors wei))




(defrecord FrontCachedWordEmbeddingIndex
  [embeddingDim
   vocabSize
   ^Map cache
   ^Map vectorsMap
   ^List tokens]
  discussion-gems.algs.protocols/WordEmbeddingIndex
  (word-embedding-dim [_this]
    embeddingDim)
  (token-vector [_this token]
    (or
      (.get cache token)
      (.get vectorsMap token)))
  (all-word-vectors [_this]
    (->> tokens
      (map (fn [token]
             [token
              (.get vectorsMap token)])))))

(defn- make-FrontCachedWordEmbeddingIndex
  [embedding-dim, ^Map vectorsMap, ^List tokens]
  (->FrontCachedWordEmbeddingIndex
    embedding-dim
    (count tokens)
    (->> tokens
      (take 256)
      (reduce
        (fn [^Map cache, token]
          (.put cache token
            (.get vectorsMap token))
          cache)
        (HashMap.)))
    vectorsMap
    tokens))





(defn parse-word2vec-line
  [l]
  (when-some [match (re-matches #"^([^\s]*)((\s\-?\d+\.\d+)+)" l)]
    (let [[_ token coords] match]
      [token
       (float-array
         (-> coords
           (str/split #"\s+")
           (->>
             (rest)
             (into []
               (map (fn [^String c]
                      (Double/parseDouble c)))))))])))

(defn load-word-embedding-index
  "Reads a Word2Vec format as provided by e.g FastText."
  [word->token n-first-vecs io-src]
  (with-open [rdr (io/reader io-src)]
    (let [vectorsMap (HashMap.)
          tokens (ArrayList.)
          [header & lines] (line-seq rdr)
          [_ _str-vocab-size str-dims] (or
                                         (re-matches #"(\d+)\s(\d+)" header)
                                         (throw
                                           (ex-info
                                             (format "Invalid header line for Word Embedding file: %s"
                                               (pr-str header))
                                             {:header-line header})))
          n-dims (Long/parseLong str-dims)]
      (-> lines
        (cond->
          (some? n-first-vecs) (->> (take n-first-vecs)))
        (->>
          (pmap
            (fn [l]
              (when-some [word+v (parse-word2vec-line l)]
                (let [[w v] word+v]
                  (when-some [token (word->token w)]
                    [token v])))))
          (filter some?)
          (run!
            (fn [[token v]]
              (when-not (.containsKey vectorsMap token)
                (.put vectorsMap token v)
                (.add tokens token))))))
      (make-FrontCachedWordEmbeddingIndex
        n-dims
        vectorsMap
        tokens))))


