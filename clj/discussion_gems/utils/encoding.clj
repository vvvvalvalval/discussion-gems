(ns discussion-gems.utils.encoding
  (:require [clojure.data.fressian :as fressian]
            [discussion-gems.config.fressian]))

(defn fressian-writer
  [out]
  (fressian/create-writer out
    :handlers discussion-gems.config.fressian/write-handlers))

(defn fressian-reader
  [in & fressian-opts]
  (apply fressian/create-reader in
    :handlers discussion-gems.config.fressian/read-handlers
    fressian-opts))

