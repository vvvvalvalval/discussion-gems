(ns discussion-gems.utils.encoding
  (:require [clojure.data.fressian :as fressian]
            [discussion-gems.config.fressian]
            [jsonista.core :as json])
  (:import (java.io ByteArrayOutputStream ByteArrayInputStream)))

(defn fressian-writer
  [out]
  (fressian/create-writer out
    :handlers discussion-gems.config.fressian/write-handlers))

(defn fressian-encode
  ^bytes [v]
  (with-open [baos (ByteArrayOutputStream.)]
    (let [wtr (fressian-writer baos)]
      (fressian/write-object wtr v)
      (.flush baos)
      (.toByteArray baos))))

(defn fressian-reader
  [in & fressian-opts]
  (apply fressian/create-reader in
    :handlers discussion-gems.config.fressian/read-handlers
    fressian-opts))

(defn fressian-decode
  [^bytes arr]
  (with-open [bais (ByteArrayInputStream. arr)]
    (let [rdr (fressian-reader bais)]
      (fressian/read-object rdr))))


(comment
  (require '[clojure.core.matrix :as matrix])

  ()

  *e)


(def json-read-mapper
  (json/object-mapper
    {:decode-key-fn true}))


(defn json-read
  [object]
  (json/read-value object json-read-mapper))
