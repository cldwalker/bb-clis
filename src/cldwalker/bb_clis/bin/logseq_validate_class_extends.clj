(ns cldwalker.bb-clis.bin.logseq-validate-class-extends
  "Fail if any user class does not extend from Thing (directly or transitively)."
  (:require [babashka.cli :as cli]
            [babashka.process :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(defn- graph-args [graph]
  (when graph ["-g" graph]))

(defn- logseq-query [graph query]
  (let [{:keys [out]} (apply shell {:out :string :continue true} "logseq" "query"
                             (concat (graph-args graph)
                                     ["-o" "edn" "--query" (pr-str query)]))
        {:keys [status data error]} (edn/read-string out)]
    (when (not= :ok status)
      (cli-util/error "Query failed:" (or (:message error) (pr-str error) out)))
    (:result data)))

(def ^:private user-class? (comp #(not (str/starts-with? % ":logseq.class/")) str))

(defn- thing-ident [graph]
  (let [results (logseq-query graph
                              '[:find ?ident
                                :where
                                [?e :db/ident ?ident]
                                [?e :block/title "Thing"]
                                [?e :block/tags :logseq.class/Tag]])
        idents (->> results (map first) (filter user-class?))]
    (cond
      (empty? idents) (cli-util/error "No user class titled 'Thing' was found.")
      (> (count idents) 1) (cli-util/error "Multiple user classes titled 'Thing' found:" (pr-str idents))
      :else (first idents))))

(defn- all-classes [graph]
  (logseq-query graph
                '[:find ?ident ?ext-ident
                  :where
                  [?e :db/ident ?ident]
                  [?e :block/tags :logseq.class/Tag]
                  [?e :logseq.property.class/extends ?p]
                  [?p :db/ident ?ext-ident]]))

(defn- thing-descendants
  "Closure of classes whose extends chain reaches `thing` (excluding `thing`)."
  [pairs thing]
  (loop [descendants #{thing}]
    (let [next-set (into descendants
                         (keep (fn [[child parent]]
                                 (when (descendants parent) child)))
                         pairs)]
      (if (= next-set descendants)
        (disj descendants thing)
        (recur next-set)))))

(defn- invalid-classes [pairs thing]
  (let [valid (conj (thing-descendants pairs thing) thing)
        by-class (reduce (fn [m [c p]] (update m c (fnil conj []) p)) {} pairs)]
    (->> by-class
         (filter (fn [[c _]] (and (user-class? c) (not (valid c)))))
         (sort-by first)
         (map (fn [[c parents]] {:class c :extends parents})))))

(defn- command [{:keys [opts]}]
  (let [graph (:graph opts)
        thing (thing-ident graph)
        pairs (all-classes graph)
        rows (invalid-classes pairs thing)]
    (if (empty? rows)
      (println "All user classes extend from Thing.")
      (do
        (println (count rows) "user class(es) do not extend from Thing:")
        (doseq [{:keys [class extends]} rows]
          (println " " class "extends" (pr-str extends)))
        (System/exit 1)))))

(def ^:private spec
  {:graph {:alias :g :desc "Graph name"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec}]
                args
                {:prog "logseq-validate-class-extends" :help true}))
