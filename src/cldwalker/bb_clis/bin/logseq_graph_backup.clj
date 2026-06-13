(ns cldwalker.bb-clis.bin.logseq-graph-backup
  "Backup one or more logseq graphs by exporting EDN and staging changes in the graph's git repo."
  (:require [babashka.tasks :refer [shell]]
            [cldwalker.bb-clis.cli :as cli]
            [clojure.data :as data]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn- prepare-export-to-diff
  "Prepare a graph's exported edn to be diffed with another"
  [m]
  (-> m
      (update :logseq.db.sqlite.export/kv-values
              (fn [kvs]
                (->> kvs
                     ;; This varies per copied graph so ignore it
                     (remove #(#{:logseq.kv/import-type :logseq.kv/imported-at :logseq.kv/local-graph-uuid}
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
        temp-edn (str (System/getenv "HOME") "/logseq/graphs/" temp-graph "/graph.edn")]
    (println "Roundtrip validation can take awhile ...")
    (shell "logseq" "graph" "import" "-t" "edn" "--timeout-ms" "30000" "--input" graph-edn "-g" temp-graph)
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

(defn- command [{:keys [options arguments summary]}]
  (cond
    (:help options)
    (cli/print-summary " GRAPH [GRAPH ...]" summary)

    (empty? arguments)
    (cli/error "At least one graph must be specified")

    (:diff options)
    (diff-files arguments)

    :else
    (doseq [graph arguments]
      (backup-graph graph options))))

(def ^:private cli-options
  [["-h" "--help"]
   ["-d" "--datoms" "Export raw datoms (:graph) instead of human-readable EDN (:graph-human)"]
   ["-D" "--diff" "Diff two graph's edn exports"]
   ["-r" "--roundtrip" "Roundtrips export by importing, exporting and comparing diff"]
   ["" "--keep" "Doesn't delete temporary roundtrip graph"]
   ["-m" "--message MESSAGE" "Git add and commit with message"]
   ["-g" "--git-show" "Show what has changed since last commit"]])

(defn -main [& args]
  (cli/run-command command args cli-options))
