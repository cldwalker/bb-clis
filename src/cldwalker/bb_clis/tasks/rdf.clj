(ns cldwalker.bb-clis.tasks.rdf
  "RDF related tasks"
  (:require [babashka.process :refer [process shell]]
            [clojure.string :as str]))

(defn rdf-equal
  "Test if multiple turtle rdf files are equal"
  {:org.babashka/cli {:spec {:files {:desc "Turtle rdf files" :coerce [] :positional true}}
                      :args->opts (repeat :files)
                      :require [:files]}}
  [{:keys [files]}]
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
  {:org.babashka/cli {:spec {:file {:desc "Turtle file" :positional true}}
                      :args->opts [:file]
                      :require [:file]}}
  [{:keys [file]}]
  (println (format "%s triples!"
                   (-> (shell {:out :string} "serdi -i turtle -o ntriples" file)
                       :out
                       str/split-lines
                       count))))
