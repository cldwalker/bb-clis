(ns cldwalker.bb-clis.tasks.rewrite-edn
  (:require [borkdude.rewrite-edn :as r]))

(defn update-gitlib
  [args]
  (let [[gitlib sha] args
        nodes (-> "deps.edn" slurp r/parse-string)]
    (spit "deps.edn"
          (str (r/assoc-in nodes [:deps (symbol gitlib) :sha] sha)))))
