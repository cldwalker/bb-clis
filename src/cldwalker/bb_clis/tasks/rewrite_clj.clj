(ns cldwalker.bb-clis.tasks.rewrite-clj
  (:require [rewrite-clj.zip :as z]))


(defn- find-symbol-first-right-sexpr
  [zloc sym]
  ;; Returns first symbol found
  (-> (z/find-value zloc z/next sym)
      z/right
      z/sexpr))

(defn var-sexp
  [[string-var file]]
  (let [zloc (z/of-string (slurp file))]
    (find-symbol-first-right-sexpr zloc (symbol string-var))))
