(ns cldwalker.bb-clis.tasks.logseq
  "Logseq related tasks"
  (:require [clojure.edn :as edn]))

(defn empty-files
  "Reads in stdin input from logseq-ast and prints files which are empty i.e. have one Heading with no title"
  []
  (let [ast-files (edn/read *in*)
        empty-files (filter
                     (fn [{:keys [ast]}]
                       (let [h1 (filter (fn [x] (= "Heading" (ffirst x))) ast)]
                         (and (= 1 (count h1)) (empty? (:title (second (ffirst h1)))))))
                     ast-files)]
    (prn (map :file empty-files))))
