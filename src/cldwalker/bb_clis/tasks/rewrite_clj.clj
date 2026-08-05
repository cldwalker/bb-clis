(ns cldwalker.bb-clis.tasks.rewrite-clj
  (:require [rewrite-clj.zip :as z]))

(defn- find-symbol-first-right-sexpr
  [zloc sym]
  ;; Returns first symbol found
  (-> (z/find-value zloc z/next sym)
      z/right
      z/sexpr))

(defn var-sexp
  "For given file, prints var sexp which is usually its value."
  {:org.babashka/cli {:spec {:var {:desc "Var name" :positional true}
                             :file {:desc "File to search" :positional true}}
                      :args->opts [:var :file]
                      :require [:var :file]}}
  [{:keys [var file]}]
  (let [zloc (z/of-string (slurp file))]
    (prn (find-symbol-first-right-sexpr zloc (symbol var)))))
