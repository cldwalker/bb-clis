(ns cldwalker.bb-clis.bin.logseq-graph-icons
  "Show icon usage stats or list nodes that use a given icon in a Logseq graph."
  (:require [babashka.tasks :refer [shell]]
            [cldwalker.bb-clis.cli :as cli]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]))

(defn- graph-args [graph]
  (when graph ["-g" graph]))

(defn- logseq-query
  ([graph query] (logseq-query graph query nil))
  ([graph query inputs]
   (let [{:keys [out]} (apply shell {:out :string} "logseq" "query"
                              (concat (graph-args graph)
                                      ["-o" "edn" "--query" (pr-str query)]
                                      (when inputs ["--inputs" (pr-str inputs)])))
         {:keys [status data]} (edn/read-string out)]
     (when (not= :ok status)
       (cli/error "Query failed:" out))
     (:result data))))

(defn- icon-stats [graph]
  (let [pairs (logseq-query graph '[:find ?e ?icon
                                    :where
                                    [?e :logseq.property/icon ?icon]
                                    (not [?e :logseq.property/built-in? true])])
        rows (->> pairs
                  (group-by #(get-in % [1 :id]))
                  (map (fn [[icon-id entries]]
                         (let [any-icon (second (first entries))]
                           {"Name" icon-id
                            "Type" (some-> (:type any-icon) name)
                            "Count" (count entries)})))
                  (sort-by #(get % "Count") >))]
    (if (empty? rows)
      (println "No nodes with :logseq.property/icon found.")
      (do
        (pprint/print-table ["Name" "Type" "Count"] rows)
        (println "Total icon usages:" (apply + (map #(get % "Count") rows)))
        (println "Unique icons:" (count rows))))))

(defn- query-icon [graph icon-id]
  (let [ids (->> (logseq-query graph
                               '[:find ?e
                                 :in $ ?target-id
                                 :where
                                 [?e :logseq.property/icon ?icon]
                                 [(get ?icon :id) ?icon-id]
                                 [(= ?icon-id ?target-id)]]
                               [icon-id])
                 (mapv first))]
    (if (empty? ids)
      (println (format "No nodes found with icon '%s'." icon-id))
      (do
        (shell "logseq" "show" "--id" (pr-str ids)
               "--level" "1"
               "--linked-references" "false"
               "--ref-id-footer" "false")
        (println "Count:" (count ids))))))

(defn- command [{:keys [options summary]}]
  (cond
    (:help options) (cli/print-summary "" summary)
    (:query options) (query-icon (:graph options) (:query options))
    :else (icon-stats (:graph options))))

(def cli-options
  [["-h" "--help"]
   ["-g" "--graph GRAPH" "Graph name"]
   ["-q" "--query ICON_ID" "Show nodes that use the given icon id (e.g. 'exclamation')"]])

(defn -main [& args]
  (cli/run-command command args cli-options))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))