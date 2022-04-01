(ns cldwalker.bb-clis.tasks.specter
  "Tasks using specter"
  (:require [com.rpl.specter :as s]))

(defn example
  []
  (s/transform [(s/walker number?) odd?] inc {:a 1 :b [1 2 3]}))
