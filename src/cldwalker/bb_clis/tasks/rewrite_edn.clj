(ns cldwalker.bb-clis.tasks.rewrite-edn
  (:require [borkdude.rewrite-edn :as r]))

(defn update-gitlib
  "Update git library sha in deps.edn."
  {:org.babashka/cli {:spec {:gitlib {:desc "Git library name" :positional true}
                             :sha {:desc "New sha" :positional true}}
                      :args->opts [:gitlib :sha]
                      :require [:gitlib :sha]}}
  [{:keys [gitlib sha]}]
  (let [nodes (-> "deps.edn" slurp r/parse-string)]
    (spit "deps.edn"
          (str (r/assoc-in nodes [:deps (symbol gitlib) :sha] sha)))))
