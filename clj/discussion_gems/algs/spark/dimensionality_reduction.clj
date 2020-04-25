(ns discussion-gems.algs.spark.dimensionality-reduction
  (:require [sparkling.core :as spark]
            [discussion-gems.utils.misc :as u]
            [sparkling.destructuring :as s-de]
            [discussion-gems.utils.spark :as uspark]
            [discussion-gems.wrappers.spark-ml])
  (:import (org.apache.spark.ml.linalg SparseVector Matrices DenseMatrix Matrix Vector)
           (org.apache.spark.mllib.linalg.distributed RowMatrix)
           (org.apache.spark.api.java JavaRDD)))

(set! *warn-on-reflection* true)
(require 'sc.api) ;; FIXME

(defrecord ppmiModel
  [^double log-n-terms
   ^double log-n-docs
   ^doubles log-term-counts])

(defn ppmi-fit
  "Collects and prepares the required statistics to transform term-frequency vectors to PPMI vectors."
  ^ppmiModel [tf-vec-rdd]
  (let [term_idx->n
        (->> tf-vec-rdd
          (spark/flat-map-to-pair
            (fn [^SparseVector tf-vec]
              (let [term-indices (.indices tf-vec)
                    counts (.values tf-vec)]
                (into
                  (mapv
                    (fn [term-idx term-doc-count]
                      (spark/tuple term-idx term-doc-count))
                    term-indices
                    counts)
                  [(spark/tuple -1 1.)
                   (spark/tuple -2 (u/arr-doubles-sum counts))]))))
          (spark/reduce-by-key +)
          (spark/sort-by-key)
          (spark/collect))]
    (if (empty? term_idx->n)
      tf-vec-rdd ;; we have to return an empty RDD, might as well be this one, as it's empty too
      (let [[p-2 p-1 & term_idx->n] term_idx->n
            log-n-terms (Math/log (double (s-de/value p-2)))
            log-n-docs (Math/log (double (s-de/value p-1)))]
        (if (empty? term_idx->n)
          tf-vec-rdd ;; we need to return null vectors, that's exactly what we have in tf-vec-rdd
          (let [vocab-size (inc (int (s-de/key (last term_idx->n))))
                log-term-counts-arr (double-array vocab-size)]
            (run!
              (fn [idx->n]
                (aset log-term-counts-arr
                  (int (s-de/key idx->n))
                  (double (Math/log (s-de/value idx->n)))))
              term_idx->n)
            (->ppmiModel log-n-terms log-n-docs log-term-counts-arr)))))))


(defn ppmi-transform-vec
  "Transforms a term-frequency vector to a PPMI (Positive Pointwise Mutual Information) vector."
  ^SparseVector [^ppmiModel ppmi-model, ^SparseVector tf-vec]
  (let [term-indices (.indices tf-vec)
        counts (.values tf-vec)
        n-terms-in-doc (u/arr-doubles-sum counts)]
    (if (zero? n-terms-in-doc)
      tf-vec
      (let [log-n-terms-in-doc (Math/log n-terms-in-doc)]
        (SparseVector.
          (.size tf-vec)
          term-indices
          (amap counts i ret
            (let [term-idx (aget term-indices i)
                  term-doc-count (aget counts i)]
              (if (zero? term-doc-count)
                0.
                (let [pmi (double
                            (-
                              (+
                                (Math/log term-doc-count)
                                (.-log_n_terms ppmi-model))
                              (+
                                (aget
                                  ^doubles (.-log_term_counts ppmi-model)
                                  term-idx)
                                log-n-terms-in-doc)))
                      ppmi (if (neg? pmi)
                             0.
                             pmi)]
                  ppmi)))))))))

(defn ppmi-transform-rdd
  [^ppmiModel ppmi-model, tf-vecs-rdd]
  (spark/map
    #(ppmi-transform-vec ppmi-model %)
    tf-vecs-rdd))



(defrecord SvdDimReductorModel
  [^Matrix sV])


(defn svd-fit
  ^SvdDimReductorModel
  [{:as _opts, n-dims ::n-dims, r-cond ::rCond
    :or {r-cond 1e-9}}
   sparse-vecs-rdd]
  (let [row-matrix
        (RowMatrix.
          (.rdd
            ^JavaRDD
            (spark/map
              discussion-gems.wrappers.spark-ml/mllib-sparse-vector
              sparse-vecs-rdd)))
        svd (.computeSVD row-matrix
              n-dims false r-cond)
        sV (.multiply
             (Matrices/diag
               (.asML (.s svd)))
             (.transpose
               (.asML (.V svd))))]
    (->SvdDimReductorModel sV)))


(defn svd-transform-vec
  [^SvdDimReductorModel svd-model, ^Vector v]
  (.multiply
    ^Matrix (.-sV svd-model)
    v))


(comment

  @(uspark/run-local
     (fn [sc]
       (-> (spark/parallelize sc
             [[[10 15 20 25 40 50]
               [3. 2. 5. 2. 6. 1.]]
              [[10 17 20]
               [4. 2. 3.]]
              [[11 15 20 22 42 50]
               [6. 4. 5. 4. 3. 1.]]])
         (->>
           (spark/map
             (fn [[indices values]]
               (SparseVector. 100
                 (int-array indices)
                 (double-array values)))))
         (as-> tf-rdd
           (let [ppmi-model (ppmi-fit tf-rdd)]
             (ppmi-transform-rdd ppmi-model tf-rdd)))
         (spark/collect))))


  (uspark/run-local
    (fn [sc]
      (let [tf-rdd
            (-> (spark/parallelize sc
                  [[[10 15 20 25 40 50]
                    [3. 2. 5. 2. 6. 1.]]
                   [[10 17 20]
                    [4. 2. 3.]]
                   [[11 15 20 22 42 50]
                    [6. 4. 5. 4. 3. 1.]]
                   [[10 18 20]
                    [4. 1. 2.]]])
              (->>
                (spark/map
                  (fn [[indices values]]
                      (SparseVector. 100
                        (int-array indices)
                        (double-array values))))))
            ppmi-rdd
            (let [ppmi-model (ppmi-fit tf-rdd)]
              (ppmi-transform-rdd ppmi-model tf-rdd))

            svd-model (svd-fit {::n-dims 3} ppmi-rdd)]
        (spark/collect
          (spark/map #(svd-transform-vec svd-model %)
            ppmi-rdd)))))
  @*1

  (def row-matrix
    (RowMatrix.))

  (def svd
    (Singular))

  *e)