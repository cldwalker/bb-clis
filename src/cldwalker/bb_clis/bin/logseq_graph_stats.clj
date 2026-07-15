(ns cldwalker.bb-clis.bin.logseq-graph-stats
  "Print page, class and property counts for a Logseq graph."
  (:require [babashka.cli :as cli]
            [babashka.tasks :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(defn- graph-args [graph]
  (when graph ["-g" graph]))

(defn- logseq-query [graph query]
  (let [{:keys [out]} (apply shell {:out :string} "logseq" "query"
                             (concat (graph-args graph)
                                     ["-o" "edn" "--query" (pr-str query)]))
        {:keys [status data]} (edn/read-string out)]
    (when (not= :ok status)
      (cli-util/error "Query failed:" out))
    (or (:result data) 0)))

(defn- logseq-list-count [graph subcommand & extra-args]
  (let [{:keys [out]} (apply shell {:out :string} "logseq" "list" subcommand
                             (concat (graph-args graph)
                                     ["-o" "edn" "--fields" "id"]
                                     extra-args))
        {:keys [status data]} (edn/read-string out)]
    (when (not= :ok status)
      (cli-util/error "list" subcommand "failed:" out))
    (count (:items data))))

(defn- builtin-class-titles [graph]
  (let [{:keys [out]} (apply shell {:out :string} "logseq" "list" "tag"
                             (concat (graph-args graph) ["-o" "edn"]))
        {:keys [status data]} (edn/read-string out)]
    (when (not= :ok status)
      (cli-util/error "list tag failed:" out))
    (->> (:items data)
         (filter #(some-> % :db/ident namespace (str/starts-with? "logseq.class")))
         (map :block/title)
         set)))

(defn- url-property-ident [graph]
  (let [{:keys [out]} (apply shell {:out :string} "logseq" "search" "property"
                             (concat (graph-args graph) ["-c" "url" "-o" "edn"]))
        {:keys [status data]} (edn/read-string out)]
    (when (not= :ok status)
      (cli-util/error "search property failed:" out))
    (some #(when (= "url" (:block/title %)) (:db/ident %)) (:items data))))

(defn- tag->url [graph]
  (if-let [ident (url-property-ident graph)]
    (->> (logseq-query graph
                       [:find '?title '?url
                        :where
                        ['?e :block/tags '?t]
                        ['?t :block/title '?title]
                        ['?t ident '?url-ref]
                        ['?url-ref :block/title '?url]])
         (into {}))
    {}))

(defn- graph-counts [graph user?]
  (let [extra (when user? ["--no-include-built-in"])]
    (array-map
     :entities (logseq-query graph '[:find (count-distinct ?e) . :where [?e _ _]])
     :pages (apply logseq-list-count graph "page" "--include-hidden" extra)
     :classes (apply logseq-list-count graph "tag" extra)
     :properties (apply logseq-list-count graph "property" extra))))

(defn- pct [num denom]
  (if (zero? denom) "n/a" (format "%.1f%%" (* 100.0 (/ num (double denom))))))

(defn- object-counts [graph {:keys [user]}]
  (let [pairs (logseq-query graph '[:find ?title (count ?e)
                                    :where [?e :block/tags ?t] [?t :block/title ?title]])
        builtins (when user (builtin-class-titles graph))
        pairs' (cond->> pairs
                 user (remove #(builtins (first %))))
        urls (tag->url graph)
        rows (->> pairs'
                  (sort-by second >)
                  (map (fn [[cls c]] {"Class" cls "Url" (get urls cls "") "Count" c})))
        total-objects (apply + (map second pairs'))
        total-classes (count pairs')
        objects-with-url (->> pairs' (filter #(get urls (first %))) (map second) (apply +))
        classes-with-url (->> pairs' (filter #(get urls (first %))) count)]
    (pprint/print-table ["Class" "Url" "Count"] rows)
    (println "Total objects:" total-objects)
    (println "Total classes:" total-classes)
    (println "Objects with url:"
             (format "%d/%d = %s"
                     objects-with-url total-objects (pct objects-with-url total-objects)))
    (println "Classes with url:"
             (format "%d/%d = %s"
                     classes-with-url total-classes (pct classes-with-url total-classes)))))

(defn- command [{:keys [opts]}]
  (if (:objects opts)
    (object-counts (:graph opts) opts)
    (pprint/pprint (graph-counts (:graph opts) (:user opts)))))

(def ^:private spec
  {:graph {:alias :g :desc "Graph name"}
   :objects {:alias :o :coerce :boolean :desc "Show tag/count breakdown for nodes with :block/tags"}
   :user {:alias :u :coerce :boolean :desc "Exclude built-in pages, classes and properties"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec}]
                args
                {:prog "logseq-graph-stats" :help true}))
