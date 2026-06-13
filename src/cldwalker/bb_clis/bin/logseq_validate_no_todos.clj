(ns cldwalker.bb-clis.bin.logseq-validate-no-todos
  "Fail if blocks contain 'td:' outside of incomplete Tasks or their references."
  (:require [babashka.tasks :refer [shell]]
            [cldwalker.bb-clis.cli :as cli]
            [clojure.edn :as edn]))

(defn- graph-args [graph]
  (when graph ["-g" graph]))

(defn- logseq-query [graph query]
  (let [{:keys [out]} (apply shell {:out :string} "logseq" "query"
                             (concat (graph-args graph)
                                     ["-o" "edn" "--query" (pr-str query)]))
        {:keys [status data]} (edn/read-string out)]
    (when (not= :ok status)
      (cli/error "Query failed:" out))
    (:result data)))

(def ^:private invalid-blocks-query
  '[:find ?b ?title
    :where
    [?b :block/title ?title]
    (or [(clojure.string/includes? ?title "td:")]
        [(= ?title "td")])
    [?b :block/parent ?p]
    (or-join [?p]
             [?p :logseq.property/status :logseq.property/status.done]
             [?p :logseq.property/status :logseq.property/status.canceled]
             (and [?p :block/refs ?t]
                  (or [?t :logseq.property/status :logseq.property/status.done]
                      [?t :logseq.property/status :logseq.property/status.canceled])))])

(defn- command [{:keys [options summary]}]
  (if (:help options)
    (cli/print-summary "" summary)
    (let [rows (->> (logseq-query (:graph options) invalid-blocks-query)
                    (map (fn [[id title]] {:id id :title title}))
                    (sort-by :id))]
      (if (empty? rows)
        (println "No invalid 'td:' or 'td' blocks found.")
        (do
          (shell "logseq" "show" "--id" (pr-str (mapv :id rows)))
          (println (count rows) "invalid block(s):" (pr-str (mapv :id rows)))
          (System/exit 1))))))

(def ^:private cli-options
  [["-h" "--help"]
   ["-g" "--graph GRAPH" "Graph name"]])

(defn -main [& args]
  (cli/run-command command args cli-options))
