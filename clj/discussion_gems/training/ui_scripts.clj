(ns discussion-gems.training.ui-scripts
  (:require [shadow.cljs.devtools.server :as server]
            [shadow.cljs.devtools.api :as shadow]))

(comment

  (server/start!)

  (shadow/watch :lab-ui-app)

  (shadow/browser-repl {:build-id :lab-ui-app})

  *1

  (println "coucou")


  :cljs/quit

  *e)


