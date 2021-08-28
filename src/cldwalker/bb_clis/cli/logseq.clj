(ns cldwalker.bb-clis.cli.logseq
  "Cli fns for logseq"
  (:require [clojure.string :as str]))

(defn block->properties [block]
  (->> block
       str/split-lines
       (map #(str/split % #"::\s*"))
       ;; TODO: warn when removing lines
       (remove #(< (count %) 2))
       (into {})))

(defn properties->block [properties]
  (->> properties
       (map (fn [[k v]] (str (name k) ":: " v)))
       (str/join "\n")))
