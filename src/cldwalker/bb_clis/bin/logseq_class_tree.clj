(ns cldwalker.bb-clis.bin.logseq-class-tree
  "Print a class tree as a markdown outline, using :logseq.property.class/extends
  for parent/child relationships."
  (:require [babashka.cli :as cli]
            [babashka.process :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.edn :as edn]
            [clojure.string :as str]))

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

(defn- class-titles [graph]
  (into {} (logseq-query graph
                         '[:find ?ident ?title
                           :where
                           [?e :db/ident ?ident]
                           [?e :block/tags :logseq.class/Tag]
                           [?e :block/title ?title]])))

(defn- extends-pairs [graph]
  (logseq-query graph
                '[:find ?ident ?ext-ident
                  :where
                  [?e :db/ident ?ident]
                  [?e :block/tags :logseq.class/Tag]
                  [?e :logseq.property.class/extends ?p]
                  [?p :db/ident ?ext-ident]]))

(defn- object-counts [graph]
  (into {} (logseq-query graph
                         '[:find ?ident (count ?obj)
                           :where
                           [?class :db/ident ?ident]
                           [?class :block/tags :logseq.class/Tag]
                           [?obj :block/tags ?class]])))

(defn- children-map
  "Map of parent ident -> sorted child idents, restricted to `included` classes."
  [pairs included titles]
  (-> (reduce (fn [m [child parent]]
                (cond-> m
                  (and (included child) (included parent))
                  (update parent (fnil conj []) child)))
              {} pairs)
      (update-vals #(sort-by (comp str/lower-case titles) (distinct %)))))

(defn- root-idents
  "Included classes with no included parent, e.g. user classes extending a
  built-in class when built-ins are excluded."
  [pairs included titles]
  (let [child->parents (reduce (fn [m [child parent]]
                                 (update m child (fnil conj []) parent))
                               {} pairs)]
    (->> included
         (remove #(some included (child->parents %)))
         (sort-by (comp str/lower-case titles)))))

(defn- idents-with-subtree-objects
  "Classes that have objects or a descendant with objects."
  [idents children counts]
  (letfn [(has-objects? [ident seen]
            (or (pos? (get counts ident 0))
                (boolean (some #(when-not (seen %)
                                  (has-objects? % (conj seen ident)))
                               (children ident)))))]
    (into #{} (filter #(has-objects? % #{})) idents)))

(defn- tree-lines
  "Depth-first seq of {:ident :depth} for visible classes under `ident`."
  [ident depth seen {:keys [children show?] :as ctx}]
  (when (and (show? ident) (not (seen ident)))
    (cons {:ident ident :depth depth}
          (mapcat #(tree-lines % (inc depth) (conj seen ident) ctx)
                  (children ident)))))

(defn- subtree-count
  "Total object count for a class and all its distinct descendants. A class
  reachable through multiple parents is counted once."
  [ident children counts]
  (letfn [(descendants [seen ident]
            (if (seen ident)
              seen
              (reduce descendants (conj seen ident) (children ident))))]
    (transduce (map #(get counts % 0)) + (descendants #{} ident))))

(defn- count-str
  "Formatted object count suffix for a class, nil when there is none to show."
  [ident {:keys [counts children show? parent-counts]}]
  (when counts
    (let [n (get counts ident 0)]
      (if (and parent-counts (some show? (children ident)))
        (if (zero? n)
          (str " (" (subtree-count ident children counts) "*)")
          (str " (" n "/" (subtree-count ident children counts) ")"))
        (when (pos? n) (str " (" n ")"))))))

(defn- command [{:keys [opts]}]
  (let [graph (:graph opts)
        titles (class-titles graph)
        included (cond-> (set (keys titles))
                   (not (:logseq-classes opts)) (->> (filter user-class?) set))
        pairs (extends-pairs graph)
        children (children-map pairs included titles)
        counts (when (:object-counts opts) (object-counts graph))
        ctx {:titles titles :children children :counts counts
             :parent-counts (:parent-counts opts)
             :show? (if counts
                      (idents-with-subtree-objects included children counts)
                      included)}
        lines (mapcat #(tree-lines % 0 #{} ctx) (root-idents pairs included titles))]
    (doseq [{:keys [ident depth]} lines]
      (println (str (str/join (repeat depth "  ")) "- " (titles ident)
                    (count-str ident ctx))))
    (println)
    (println "Total classes:" (count lines))
    (when counts
      (println "Total objects:"
               (transduce (comp (map :ident) (distinct) (map #(get counts % 0)))
                          + lines)))))

(def ^:private spec
  {:graph {:alias :g :desc "Graph name"}
   :logseq-classes {:alias :l :coerce :boolean
                    :desc "Also print built-in logseq classes"}
   :object-counts {:alias :o :coerce :boolean
                   :desc "Append object count to each class"}
   :parent-counts {:alias :p :coerce :boolean
                   :desc "With -o, show parent classes as 'individual / subtree total'"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec}]
                args
                {:prog "logseq-class-tree" :help true}))
