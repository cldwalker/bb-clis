(ns cldwalker.bb-clis.cli
  "Common fns for babashka/clojure CLIs"
  (:require [clojure.string :as str]))

(defn error
  "Print error message(s) and exit"
  [& msgs]
  (apply println "Error:" msgs)
  (System/exit 1))

(defn split-leading-opts
  "Consume leading options from `args` that match a babashka.cli `spec`, and
  return `[our-args rest-args]`. Useful for CLIs that need bb.cli to parse a
  known set of leading options and then pass the remaining args through to a
  wrapped command (bb.cli doesn't have a direct `:in-order` equivalent)."
  [spec args]
  (let [flags-for (fn [pred]
                    (into #{}
                          (mapcat (fn [[k opts]]
                                    (when (pred opts)
                                      (cond-> [(str "--" (name k))]
                                        (:alias opts) (conj (str "-" (name (:alias opts))))))))
                          spec))
        boolean-opt? #(= :boolean (:coerce %))
        value-flags (flags-for (complement boolean-opt?))
        bool-flags (flags-for boolean-opt?)
        eq-prefixes (mapv #(str "--" (name %) "=") (keys spec))]
    (loop [our [] rest args]
      (let [a (first rest)]
        (cond
          (empty? rest) [our rest]
          (contains? value-flags a) (recur (into our (take 2 rest)) (drop 2 rest))
          (contains? bool-flags a) (recur (conj our a) (next rest))
          (some #(str/starts-with? a %) eq-prefixes) (recur (conj our a) (next rest))
          :else [our rest])))))
