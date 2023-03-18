(ns cldwalker.bb-clis.tasks.rdf
  "RDF related tasks"
  (:require [babashka.process :refer [process shell]]
            [clojure.string :as str]))

(defn rdf-equal
  "Test if multiple turtle rdf files are equal"
  [& files]
  (let [write-nt
        (fn [in-file out-file]
          (println "Write" out-file)
          (spit out-file
                (-> (process "serdi -i turtle -o ntriples" in-file)
                    (process {:out :string} "sort")
                    deref
                    :out)))
        file-pairs (map (fn [x] (vector x (str/replace-first x ".ttl" ".nt")))
                        files)]
    (doseq [file-pair file-pairs]
      (apply write-nt file-pair))
    (apply shell "diff" (map second file-pairs))
    (println "Success!")))

(defn triples-count
  "Count number of triples in a turtle file"
  [file]
  (println (format "%s triples!"
                   (-> (shell {:out :string} "serdi -i turtle -o ntriples" file)
                       :out
                       str/split-lines
                       count))))
