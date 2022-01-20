(ns cldwalker.bb-clis.tasks.logseq
  "Logseq related tasks"
  (:require [clojure.edn :as edn]))

(defn empty-files
  "Reads in stdin input from logseq-ast and prints files which have no content"
  ;; Empty is no nodes or one node with blank heading
  ;; Worked for all except one node with a property drawer that wasn't just timestamps
  []
  (let [ast-files (edn/read *in*)
        empty-files (filter
                     (fn [{:keys [ast]}]
                       (let [h1 (filter (fn [x] (= "Heading" (ffirst x))) ast)]
                         (or (zero? (count h1))
                             (and (= 1 (count h1)) (empty? (:title (second (ffirst h1))))))))
                     ast-files)]
    (prn (map :file empty-files))))
