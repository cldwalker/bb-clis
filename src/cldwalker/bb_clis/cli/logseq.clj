(ns cldwalker.bb-clis.cli.logseq
  "Cli fns for logseq"
  (:require [clojure.string :as str]))

(defn properties->block [properties]
  (->> properties
       (map (fn [[k v]] (str (name k) ":: " v)))
       (str/join "\n")))
