(ns discussion-gems.algs.linalg)


(defn vec-constant
  [dim c]
  (float-array (int dim) (float c)))

(defn vec-scale
  [lambda, ^floats x]
  (let [lambda (float lambda)]
    (amap x i ret
      (float
        (* lambda
          (aget x i))))))

(defn vec-add
  ([^floats x] x)
  ([^floats x, ^floats y]
   (amap x i ret
     (float
       (+
         (aget x i)
         (aget y i)))))
  ([x, y & zs]
   (reduce
     (fn [^floats sum, ^floats z]
       (areduce z i acc nil
         (do
           (aset sum i
             (+
               (aget sum i)
               (aget z i)))
           nil))
       sum)
     (vec-add x y)
     zs)))

(defn vec-mean
  [vs]
  (vec-scale
    (/ 1. (count vs))
    (apply vec-add vs)))


(defn vec-dot-product
  [^floats x, ^floats y]
  (areduce x i acc
    (float 0.)
    (+ (float acc)
      (* (aget x i) (aget y i)))))

(defn vec-norm2
  [x]
  (Math/sqrt
    (vec-dot-product x x)))

(defn vec-cosine-sim
  [x y]
  (double
    (let [nx (vec-norm2 x)
          ny (vec-norm2 y)]
      (if (or (zero? nx) (zero? ny))
        0.
        (/ (vec-dot-product x y)
          (* nx ny))))))


(defn vec-project-unit
  [x]
  (vec-scale
    (/ 1. (vec-norm2 x))
    x))
