(ns cldwalker.bb-clis.bin.bb-ns-dep-tree
  "Prints ascii tree of ns dependencies like tree command"
  (:require [babashka.cli :as cli]
            [babashka.pods :as pods]
            [clojure.java.io :as io]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(pods/load-pod "clj-kondo")
(require '[pod.borkdude.clj-kondo :as clj-kondo])

;; Ascii tree from https://github.com/babashka/babashka/blob/master/examples/tree.clj
(def ^:private I-branch "│   ")
(def ^:private T-branch "├── ")
(def ^:private L-branch "└── ")
(def ^:private SPACER   "    ")

(def ^:private already-seen (atom #{}))

(defn- build-tree
  [node children-map {:keys [expand-duplicate-branches] :as opts}]
  (let [can-expand? (or expand-duplicate-branches
                        (not (contains? @already-seen node)))
        children (children-map node)
        parent? (seq children)]
    (swap! already-seen conj node)
    (cond-> {:name (str node (when (and parent? (not can-expand?)) " ..."))
             :type (if parent? "parent" "child")}
            (and parent? can-expand?) (assoc :children
                                             (map #(build-tree % children-map opts)
                                                  children)))))

(defn- render-tree
  [{:keys [name children]}]
  (cons name
        (mapcat
         (fn [child index]
           (let [subtree (render-tree child)
                 last? (= index (dec (count children)))
                 prefix-first (if last? L-branch T-branch)
                 prefix-rest  (if last? SPACER I-branch)]
             (cons (str prefix-first (first subtree))
                   (map #(str prefix-rest %) (next subtree)))))
         children
         (range))))

(defn- clj-kondo-analysis
  [paths]
  (:analysis (clj-kondo/run! {:lint paths
                              :config {:output {:analysis true}}})))

(defn- build-children-map [analysis lang-to-match]
  (reduce (fn [acc {:keys [from to lang]}]
            (if (or (nil? lang) (= lang-to-match lang))
              (update acc from (fnil conj []) to)
              acc))
          {}
          (:namespace-usages analysis)))

(defn- print-ns-tree [ns-or-file {:keys [source-paths lang] :as options}]
  (let [analysis (clj-kondo-analysis source-paths)
        ns-sym (if (.exists (io/file ns-or-file))
                 (some #(when (= ns-or-file (:filename %)) (:name %))
                       (:namespace-definitions analysis))
                 (symbol ns-or-file))
        tree (build-tree ns-sym (build-children-map analysis lang) options)]
    (doseq [l (render-tree tree)]
      (println l))))

(def ^:private spec
  {:ns-or-file {:positional true :coerce :string :desc "Namespace or file" :require true}
   :expand-duplicate-branches {:alias :e :coerce :boolean :desc "Expands all ns dependencies including duplicate branches"}
   :lang {:alias :l :coerce :keyword :default :clj :desc "Language for .cljc analysis"}
   :source-paths {:alias :s :coerce [:string] :default ["src"]
                  :validate {:pred #(every? (fn [d] (.isDirectory (io/file d))) %)
                             :ex-msg (constantly "Must be a valid directory")}
                  :desc "Source paths to analyze"}})

(defn- command [{:keys [opts]}]
  (print-ns-tree (:ns-or-file opts) opts))

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec :args->opts [:ns-or-file] :restrict-args true}]
                args
                {:prog "bb-ns-dep-tree" :help true}))
