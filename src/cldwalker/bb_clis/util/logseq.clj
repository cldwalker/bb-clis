(ns cldwalker.bb-clis.util.logseq
  "Logseq utility functions."
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.edn :as edn]))

(defn- graphs
  "Returns a vector of graph names from `logseq graph list -o edn`."
  []
  (try
    (let [{:keys [out]} (shell {:out :string :continue true} "logseq" "graph" "list" "-o" "edn")
          {:keys [status data]} (edn/read-string out)]
      (when (= :ok status)
        (:graphs data)))
    (catch Exception _ nil)))

(defn complete-graphs
  "Completion candidate fn for babashka.cli `:complete-fn` on `:graph` options."
  [_]
  (graphs))

(defn graph-args
  "`logseq` CLI args that select GRAPH, or nil to use its default graph."
  [graph]
  (when graph ["-g" graph]))

(defn logseq-query
  "Runs `logseq query` with the given Datascript QUERY and INPUTS against GRAPH,
  returning its :result or erroring on failure."
  [graph query inputs]
  (let [{:keys [out]} (apply shell {:out :string :continue true} "logseq" "query"
                             (concat (graph-args graph)
                                     ["-o" "edn" "--query" (pr-str query)]
                                     (when inputs ["--inputs" (pr-str inputs)])))
        {:keys [status data error]} (edn/read-string out)]
    (when (not= :ok status)
      (cli-util/error "Query failed:" (or (:message error) (pr-str error) out)))
    (:result data)))

(defn existing-idents
  "Returns the subset of the given IDENTS that exist in GRAPH"
  [graph idents]
  (if (empty? idents)
    #{}
    (set (logseq-query graph
                       '[:find [?ident ...] :in $ [?ident ...] :where [?e :db/ident ?ident]]
                       [(vec idents)]))))

(defn existing-property-titles
  "Returns the subset of the given TITLES that already exist as a property in
  GRAPH. Checks by title, not :db/ident, since importing a property whose
  title matches an existing one under a different ident creates an unusable
  duplicate."
  [graph titles]
  (if (empty? titles)
    #{}
    (set (logseq-query graph
                       '[:find [?title ...] :in $ [?title ...]
                         :where
                         [?e :block/title ?title]
                         [?e :block/tags :logseq.class/Property]]
                       [(vec titles)]))))

(defn existing-user-class-titles
  "Returns the subset of the given TITLES that already exist as a user class in
  GRAPH. Only considers user classes (no :logseq.property/built-in?) so that
  schema.org classes can coexist with same-named built-ins e.g. Comment. Checks
  by title since importing a class whose title matches an existing user class
  under a different ident creates an unusable duplicate."
  [graph titles]
  (if (empty? titles)
    #{}
    (set (logseq-query graph
                       '[:find [?title ...] :in $ [?title ...]
                         :where
                         [?e :block/title ?title]
                         [?e :block/tags :logseq.class/Tag]
                         (not [?e :logseq.property/built-in? _])]
                       [(vec titles)]))))

(defn import-edn
  "Imports EDN-MAP (graph-ontology export format) into GRAPH via `logseq graph
  import`."
  [graph edn-map]
  (let [temp-file (fs/create-temp-file {:prefix "logseq-schema" :suffix ".edn"})]
    (try
      (spit (fs/file temp-file) (pr-str edn-map))
      (let [{:keys [exit]} (apply shell {:continue true} "logseq" "graph" "import"
                                  (concat (graph-args graph) ["--type" "edn" "--input" (str temp-file)]))]
        (when-not (zero? exit)
          (cli-util/error "Import failed with exit code" exit)))
      (finally (fs/delete-if-exists temp-file)))))
