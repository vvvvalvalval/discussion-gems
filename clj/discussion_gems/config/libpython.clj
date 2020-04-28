(ns discussion-gems.config.libpython
  (:require [libpython-clj.python :as py]
            [clojure.java.io :as io]))


(def potential-paths
  [{:python-executable "/home/ubuntu/miniconda3/envs/discussion_gems_condaenv/bin/python"
    :library-path "/home/ubuntu/miniconda3/envs/discussion_gems_condaenv/lib/libpython3.7m.so"}
   {:python-executable "/Users/val/opt/anaconda3/bin/python"
    :library-path "/Users/val/opt/anaconda3/lib/libpython3.7m.dylib"}])

(apply py/initialize!
  (->
    (->> potential-paths
      (filter
        #(-> % :library-path (io/file) .exists)))
    first
    (or (throw (ex-info "No matching config for libpython" {})))
    (->>
      (into [] cat))))
