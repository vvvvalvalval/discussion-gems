(ns discussion-gems.sandbox.python
  (:require [discussion-gems.config.libpython]
            [libpython-clj.require :refer [require-python]]
            [libpython-clj.python :refer [py. py.. py.-] :as py]
            [tech.v2.datatype :as dtype]
            [clojure.repl :refer :all]))

(require-python '[numpy :as np])

(def test-ary (np/array [[1 2][3 4]]))


(require-python '[discussion_gems_py.test_module])

(discussion_gems_py.test_module/addition 3. 4.)
