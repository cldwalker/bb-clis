(ns cldwalker.bb-clis.bin.logseq-graph-stats
  "Print page, class and property counts for a Logseq graph."
  (:require [babashka.cli :as cli]
            [babashka.process :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

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

(defn- tag->url [graph ident]
  (if ident
    (->> (logseq-query graph
                       [:find '?title '?url
                        :where
                        ['?e :block/tags '?t]
                        ['?t :block/title '?title]
                        ['?t ident '?url-ref]
                        ['?url-ref :block/title '?url]])
         (into {}))
    {}))

(defn- objects-with-url-pairs
  "Per class, count of objects that have their own url property value."
  [graph ident]
  (if ident
    (logseq-query graph
                  [:find '?title '(count ?e)
                   :where
                   ['?e :block/tags '?t]
                   ['?t :block/title '?title]
                   ['?e ident]])
    []))

(defn- graph-counts [graph user?]
  (let [extra (when user? ["--include-built-in" "false"])]
    (array-map
     :entities (logseq-query graph '[:find (count-distinct ?e) . :where [?e _ _]])
     :pages (apply logseq-list-count graph "page" "--include-hidden" extra)
     :classes (apply logseq-list-count graph "tag" extra)
     :properties (apply logseq-list-count graph "property" extra))))

(defn- truncate [s length]
  (if (> (count s) length) (str (subs s 0 length) "...") s))

(defn- pct [num denom]
  (if (zero? denom) "n/a" (format "%.1f%%" (* 100.0 (/ num (double denom))))))

(defn- object-counts [graph {:keys [user]}]
  (let [pairs (logseq-query graph '[:find ?title (count ?e)
                                    :where [?e :block/tags ?t] [?t :block/title ?title]])
        builtins (when user (builtin-class-titles graph))
        remove-builtins (fn [pairs]
                          (cond->> pairs
                            user (remove #(builtins (first %)))))
        pairs' (remove-builtins pairs)
        ident (url-property-ident graph)
        urls (tag->url graph ident)
        rows (->> pairs'
                  (sort-by second >)
                  (map (fn [[cls c]] {"Class" cls "Url" (truncate (get urls cls "") 80) "Count" c})))
        total-objects (apply + (map second pairs'))
        total-classes (count pairs')
        objects-with-url (->> pairs' (filter #(get urls (first %))) (map second) (apply +))
        classes-with-url (->> pairs' (filter #(get urls (first %))) count)
        objects-with-own-url (->> (objects-with-url-pairs graph ident)
                                  remove-builtins
                                  (map second)
                                  (apply +))]
    (pprint/print-table ["Class" "Url" "Count"] rows)
    (println "Total objects:" total-objects)
    (println "Total classes:" total-classes)
    (println "Classes with url:"
             (format "%d/%d = %s"
                     classes-with-url total-classes (pct classes-with-url total-classes)))
    (println "Objects with class url:"
             (format "%d/%d = %s"
                     objects-with-url total-objects (pct objects-with-url total-objects)))
    (println "Objects with url:"
             (format "%d/%d = %s"
                     objects-with-own-url total-objects (pct objects-with-own-url total-objects)))))

(defn- object-urls-table
  "Print all objects with their own url, no urls first."
  [graph {:keys [user]}]
  (let [ident (url-property-ident graph)
        objects (logseq-query graph '[:find ?e ?title ?class
                                      :where
                                      [?e :block/tags ?t]
                                      [?e :block/title ?title]
                                      [?t :block/title ?class]])
        builtins (when user (builtin-class-titles graph))
        objects' (cond->> objects
                   user (remove #(builtins (nth % 2))))
        e->url (if ident
                 (->> (logseq-query graph
                                    [:find '?e '?url
                                     :where
                                     ['?e :block/tags]
                                     ['?e ident '?url-ref]
                                     ['?url-ref :block/title '?url]])
                      (into {}))
                 {})
        rows (->> objects'
                  (map (fn [[e title _class]] [e title]))
                  distinct
                  (map (fn [[e title]] {"Title" (truncate title 50) "Url" (truncate (get e->url e) 50)}))
                  (sort-by (juxt #(if (nil? (get % "Url")) 0 1)
                                 #(get % "Title"))))]
    (pprint/print-table ["Title" "Url"] rows)
    (println "Objects with url:"
             (format "%d/%d = %s"
                     (count (filter #(get % "Url") rows)) (count rows)
                     (pct (count (filter #(get % "Url") rows)) (count rows))))))

(defn- command [{:keys [opts]}]
  (if (:object-urls opts)
    (object-urls-table (:graph opts) opts)
    (do
      (println "Object Counts by Class:")
      (object-counts (:graph opts) opts)
      (println "\nCounts:")
      (pprint/pprint (graph-counts (:graph opts) (:user opts))))))

(def ^:private spec
  {:graph {:alias :g :desc "Graph name"}
   :object-urls {:alias :o :coerce :boolean
                 :desc "Display all objects and their urls, no urls first"}
   :user {:alias :u :coerce :boolean :desc "Exclude built-in pages, classes and properties"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec}]
                args
                {:prog "logseq-graph-stats" :help true}))
