(ns cldwalker.bb-clis.bin.logseq-graph-icons
  "Show icon usage stats or list nodes that use a given icon in a Logseq graph."
  (:require [babashka.cli :as cli]
            [babashka.tasks :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

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
       (cli-util/error "Query failed:" out))
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

(defn- command [{:keys [opts]}]
  (if (:query opts)
    (query-icon (:graph opts) (:query opts))
    (icon-stats (:graph opts))))

(def ^:private spec
  {:graph {:alias :g :desc "Graph name"}
   :query {:alias :q :desc "Show nodes that use the given icon id (e.g. 'exclamation')"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec}]
                args
                {:prog "logseq-graph-icons" :help true}))
