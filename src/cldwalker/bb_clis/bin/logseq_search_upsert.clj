(ns cldwalker.bb-clis.bin.logseq-search-upsert
  "Search blocks via `logseq search block -c`, then `logseq upsert task` chosen ids."
  (:require [babashka.tasks :refer [shell]]
            [cldwalker.bb-clis.util.input :as input]
            [clojure.string :as str]))

(defn- parse-results
  [out]
  (->> (str/split-lines out)
       (keep (fn [line]
               (when-let [[_ id title] (re-matches #"(\d+)\s+(.*)" line)]
                 [(parse-long id) title])))))

(defn -main [& args]
  (when (empty? args)
    (println "Usage: logseq-search-upsert QUERY [& ARGS]")
    (System/exit 1))
  (let [[content upsert-opts] (split-with #(not (str/starts-with? % "-")) args)
        out (:out (shell {:out :string} "logseq" "search" "block" "-c" (str/join " " content)))
        results (vec (parse-results out))
        n (count results)]
    (if (zero? n)
      (println "No results.")
      (do
        (doseq [[i [_ title]] (map-indexed vector results)]
          (printf "%d. %s%n" (inc i) title))
        (print "\nSelect items: ")
        (flush)
        (let [input (or (read-line) "")
              indices (->> (input/parse-multi-select input n) distinct sort)
              invalid (remove #(<= 1 % n) indices)]
          (cond
            (empty? indices)
            (println "Nothing selected.")

            (seq invalid)
            (do (println "Invalid indices:" (str/join "," invalid))
                (System/exit 1))

            :else
            (doseq [id (mapv #(first (results (dec %))) indices)]
              (apply shell "logseq" "upsert" "block" "--id" (str id) upsert-opts))))))))
