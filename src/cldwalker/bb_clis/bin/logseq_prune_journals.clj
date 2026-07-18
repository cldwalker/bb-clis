(ns cldwalker.bb-clis.bin.logseq-prune-journals
  "Find journals with no/blank children and no linked references; pretend or delete them."
  (:require [babashka.cli :as cli]
            [babashka.process :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(defn- graph-args [graph]
  (when graph ["-g" graph]))

(defn- logseq-edn [graph & args]
  (let [{:keys [out]} (apply shell {:out :string} "logseq"
                             (concat args (graph-args graph) ["-o" "edn"]))
        {:keys [status data]} (edn/read-string out)]
    (when (not= :ok status)
      (cli-util/error "Command failed:" out))
    data))

(defn- empty-journal? [{:keys [root linked-references]}]
  (and (zero? (:count linked-references))
       (every? #(str/blank? (:block/title %))
               (:block/children root))))

(defn- todays-journal-uuid-prefix []
  ;; Logseq journal uuids are of the form 00000001-YYYY-MMDD-0000-000000000000
  (let [today (java.time.LocalDate/now)]
    (format "00000001-%04d-%02d%02d"
            (.getYear today) (.getMonthValue today) (.getDayOfMonth today))))

(defn- empty-journals [graph]
  (let [today-prefix (todays-journal-uuid-prefix)
        journal-ids (->> (logseq-edn graph "list" "page" "--journal-only" "-e")
                         :items
                         (remove #(str/starts-with? (str (:block/uuid %)) today-prefix))
                         (mapv :db/id))]
    (->> (logseq-edn graph "show" "--id" (pr-str journal-ids))
         (filter empty-journal?)
         (map (fn [{:keys [root]}]
                {:id (:db/id root)
                 :title (:block/title root)
                 :block-count (count (:block/children root))}))
         (sort-by :id))))

(defn- print-summary [rows]
  (pprint/print-table
    ["ID" "Journal" "Block Count"]
    (map (fn [{:keys [id title block-count]}]
           {"ID" id "Journal" title "Block Count" block-count})
         rows)))

(defn- command [{:keys [opts]}]
  (let [{:keys [graph pretend]} opts
        rows (empty-journals graph)]
    (cond
      (empty? rows)
      (println "No empty journals found.")

      pretend
      (let [ids (mapv :id rows)]
        (apply shell "logseq" "show"
               (concat (graph-args graph) ["--id" (pr-str ids)]))
        (println)
        (print-summary rows)
        (println "Total: " (count rows))
        (System/exit 1))

      :else
      (do
        (doseq [{:keys [id title]} rows]
          (println "Removing" title)
          ;; TODO: Permanently delete pages when it's possible
          (apply shell "logseq" "remove" "page"
                 (concat (graph-args graph) ["--id" (str id)])))
        (println "Recycled" (count rows) "journals")))))

(def ^:private spec
  {:graph {:alias :g :desc "Graph name"}
   :pretend {:alias :n :coerce :boolean :desc "Preview journals that would be deleted"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec}]
                args
                {:prog "logseq-prune-journals" :help true}))
