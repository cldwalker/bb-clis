(ns cldwalker.bb-clis.util.input
  "Fns for parsing user input on the command line."
  (:require [clojure.string :as str]))

(defn- expand-part [part]
  (if (str/includes? part "-")
    (let [[a b] (str/split part #"-")]
      (range (parse-long a) (inc (parse-long b))))
    [(parse-long part)]))

(defn parse-multi-select
  "Parses a 1-indexed multi-select input string against a list of `n` items and
   returns the selected indices. Supports comma-separated indices, ranges via
   `-`, and `*` to select all. E.g. for `n` = 5, `\"1,3-4\"` returns `(1 3 4)`
   and `\"*\"` returns `(1 2 3 4 5)`."
  [input n]
  (let [input (str/trim input)]
    (if (= input "*")
      (range 1 (inc n))
      (->> (str/split input #",")
           (map str/trim)
           (remove str/blank?)
           (mapcat expand-part)))))
