(ns cldwalker.bb-clis.bin.logseq-graph-backup
  "Backup one or more logseq graphs by exporting EDN and staging changes in the graph's git repo."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(defn- prepare-export-to-diff
  "Prepare a graph's exported edn to be diffed with another"
  [m]
  (-> m
      (update :logseq.db.sqlite.export/kv-values
              (fn [kvs]
                (->> kvs
                     ;; This varies per copied graph so ignore it
                     (remove #(#{:logseq.kv/import-type :logseq.kv/imported-at :logseq.kv/local-graph-uuid
                                 ;; TODO: Add upstream
                                 :logseq.kv/recycle-last-gc-at}
                               (:db/ident %)))
                     (sort-by :db/ident)
                     vec)))))

;; Copied from l.db.sqlite.export
(defn- diff-exports
  "Given two graph export edns, return a vector of diffs when there is a diff and nil when there is
     no diff between the two"
  [export-map export-map2]
  (let [diff (->> (data/diff (prepare-export-to-diff export-map) (prepare-export-to-diff export-map2))
                  butlast)]
    (when-not (= [nil nil] diff)
      diff)))

(defn- diff-files*
  [file1 file2]
  (let [export-map  (edn/read-string (slurp file1))
        export-map2 (edn/read-string (slurp file2))
        diff (diff-exports export-map export-map2)]
      (if diff
        (do (println "The exported EDN's have the following diff:")
            (pprint/pprint diff)
            (System/exit 1))
        (println "The exported EDN roundtrips successfully!"))))

(defn- roundtrip-graph
  "Roundtrips the exported EDN through a temp graph and diffs the re-export.
   Leaves the temp graph and temp EDN behind on failure so they can be inspected."
  [graph graph-edn export-options {:keys [keep]}]
  (let [temp-graph (str graph "-roundtrip" #_(System/currentTimeMillis))
        temp-graph-dir (str (System/getenv "HOME") "/logseq/graphs/" temp-graph)
        temp-edn (str temp-graph-dir "/graph.edn")]
    (println "Roundtrip validation can take awhile ...")
    ;; Always start from a clean graph: importing EDN into an existing graph is
    ;; additive (blocks aren't deduplicated against existing ones), so a leftover
    ;; temp graph from a prior failed run would cause every block to duplicate.
    (when (fs/exists? temp-graph-dir)
      (shell "logseq" "graph" "remove" "-g" temp-graph))
    (shell "logseq" "graph" "import" "-t" "edn" "--timeout-ms" "60000" "--input" graph-edn "-g" temp-graph)
    (shell "logseq" "graph" "validate" "-g" temp-graph)
    (shell "logseq" "graph" "export" "-t" "edn" "-e" export-options "-p" "-g" temp-graph "--file" temp-edn)
    ;; Don't care about diffing datoms
    (when (not= "{:export-type :graph}" export-options)
      (diff-files* graph-edn temp-edn))
    (when-not keep
      (shell "logseq" "graph" "remove" "-g" temp-graph))))

(defn- backup-graph [graph {:keys [message roundtrip datoms git-show] :as options}]
  (let [graph-dir (str (System/getenv "HOME") "/logseq/graphs/" graph)
        graph-edn (str graph-dir "/graph.edn")
        export-options (if datoms "{:export-type :graph}" "{:export-type :graph-human}")]
    (println "==>" graph)
    (shell "logseq" "graph" "export" "-t" "edn" "-e" export-options "-p" "-g" graph "--file" graph-edn)
    (when roundtrip (roundtrip-graph graph graph-edn export-options options))
    (when message
      (shell {:dir graph-dir} "git" "add" "-u")
      (shell {:dir graph-dir} "git" "add" "mirror")
      (shell {:dir graph-dir} "git" "commit" "-m" message))
    (when git-show
      (if message
        (shell {:dir graph-dir} "git" "show" "graph.edn" "mirror")
        ;; only graph.edn since mirror is incomplete without git actions
        (shell {:dir graph-dir} "git" "diff" "graph.edn")))))

(defn- diff-files [[graph temp-edn*]]
  (let [graph-edn (str (System/getenv "HOME") "/logseq/graphs/" graph "/graph.edn")
        temp-edn (if (str/includes? (str temp-edn*) "/") temp-edn*
                     (str (System/getenv "HOME") "/logseq/graphs/" temp-edn* "/graph.edn"))]
    (diff-files* graph-edn temp-edn)))

(defn- command [{:keys [opts]}]
  (let [graphs (:graphs opts)]
    (if (:diff opts)
      (diff-files graphs)
      (doseq [graph graphs]
        (backup-graph graph opts)))))

(def ^:private spec
  {:graphs {:positional true :coerce [:string] :require true :desc "Graphs to backup"}
   :datoms {:alias :d :coerce :boolean :desc "Export raw datoms (:graph) instead of human-readable EDN (:graph-human)"}
   :diff {:alias :D :coerce :boolean :desc "Diff two graph's edn exports"}
   :roundtrip {:alias :r :coerce :boolean :desc "Roundtrips export by importing, exporting and comparing diff"}
   :keep {:coerce :boolean :desc "Doesn't delete temporary roundtrip graph"}
   :message {:alias :m :desc "Git add and commit with message"}
   :git-show {:alias :g :coerce :boolean :desc "Show what has changed since last commit"}})

(defn -main [& args]
  ;; (repeat :graphs) collects positionals anywhere so options can follow graph args
  (cli/dispatch [{:cmds [] :fn command :spec spec :args->opts (repeat :graphs)}]
                args
                {:prog "logseq-graph-backup" :help true}))
