(ns discussion-gems.config.libpython
  (:require [libpython-clj.python :as py]))


(py/initialize!
  :python-executable "\"/home/ubuntu/miniconda3/envs/discussion_gems_condaenv/bin/python\""
  :library-path "/home/ubuntu/miniconda3/envs/discussion_gems_condaenv/lib/libpython3.7m.so")

(comment
  (py/initialize!
    :python-executable "/Users/val/opt/anaconda3/bin/python"
    :library-path "/Users/val/opt/anaconda3/lib/libpython3.7m.dylib"))
