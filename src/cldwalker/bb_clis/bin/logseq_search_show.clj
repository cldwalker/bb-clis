(ns cldwalker.bb-clis.bin.logseq-search-show
  "Search blocks via `logseq search block -c`, then `logseq show` chosen ids."
  (:require [babashka.cli :as cli]
            [babashka.process :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.input :as input]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(def ^:private spec
  {:query {:positional true :coerce [:string] :require true
           :desc "Search query"}
   :select {:alias :s :coerce :string :desc "Select results without prompting e.g. `1,3-4` or `*`"}})

(defn- parse-results
  [out]
  (->> (str/split-lines out)
       (keep (fn [line]
               (when-let [[_ id title] (re-matches #"(\d+)\s+(.*)" line)]
                 [(parse-long id) title])))))

(defn- search-and-show [query show-opts select]
  (let [out (:out (shell {:out :string} "logseq" "search" "block" "-c" query))
        results (vec (parse-results out))
        n (count results)]
    (if (zero? n)
      (println "No results.")
      (do
        (doseq [[i [_ title]] (map-indexed vector results)]
          (printf "%d. %s%n" (inc i) title))
        (flush)
        (let [input (or select
                        (do (print "\nSelect items: ")
                            (flush)
                            (read-line))
                        "")
              indices (->> (input/parse-multi-select input n) distinct sort)
              invalid (remove #(<= 1 % n) indices)]
          (cond
            (empty? indices)
            (println "Nothing selected.")

            (seq invalid)
            (do (println "Invalid indices:" (str/join "," invalid))
                (System/exit 1))

            :else
            (let [ids (mapv #(first (results (dec %))) indices)]
              (apply shell "logseq" "show" "--id" (pr-str ids) show-opts))))))))

(defn- command
  "Computes SHOW-ARGS from raw args instead of dispatch's parsed input so
  unrecognized options pass through to `logseq show` untouched. Our options are
  accepted before QUERY or immediately after it; remaining options are SHOW-ARGS."
  [args {:keys [query select]}]
  (let [[_our-args rest-args] (cli-util/split-leading-opts spec args)
        [_query-args post-query-args] (split-with #(not (str/starts-with? % "-")) rest-args)
        [_more-our-args show-opts] (cli-util/split-leading-opts spec post-query-args)]
    (search-and-show (str/join " " query) show-opts select)))

(defn -main [& args]
  (cli/dispatch [{:cmds [] :spec spec :args->opts (repeat :query)
                  :doc "Search blocks via `logseq search block -c`, then `logseq show` chosen ids."
                  :epilog "Options after <query> that aren't listed above are passed through to `logseq show`."
                  :fn (fn [{:keys [opts]}] (command args opts))}]
                args
                {:prog "logseq-search-show" :help true}))
