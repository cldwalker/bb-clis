(ns cldwalker.bb-clis.bin.bb-unused-vars
  "A slightly modified version of https://github.com/borkdude/clj-kondo/blob/master/analysis/src/clj_kondo/tools/unused_vars.clj"
  (:require [babashka.cli :as cli]
            [babashka.pods :as pods]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(pods/load-pod "clj-kondo")
(require '[pod.borkdude.clj-kondo :as clj-kondo])

(defn- check-unused-vars
  "Checks for unused vars. If found prints them and fails with exit 1."
  [paths {:keys [ignore-file]}]
  (let [analysis (:analysis (clj-kondo/run! {:lint paths
                                             :config {:output {:analysis true}}}))
        {:keys [:var-definitions :var-usages]} analysis
        defined-vars (set (map (juxt :ns :name) var-definitions))
        used-vars (cond-> (set (map (juxt :to :name) var-usages))
                          ignore-file
                          (set/union (set (map
                                           (juxt (comp symbol namespace) (comp symbol name))
                                           (edn/read-string (slurp ignore-file))))))
        unused-vars (map (fn [[ns v]]
                           (symbol (str ns) (str v)))
                         (set/difference defined-vars used-vars))]
    (if (seq unused-vars)
      (do (println "The following vars are unused:")
        (println (str/join "\n" unused-vars))
        (System/exit 1))
      (do (println "No unused vars found.")
        (System/exit 0)))))

(defn- command [{:keys [opts]}]
  (check-unused-vars (:source-paths opts) opts))

(def ^:private spec
  {:source-paths {:positional true :coerce [:string] :require true :desc "Source paths to analyze"}
   :ignore-file {:alias :i :desc "EDN file of vars to ignore"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec :args->opts (repeat :source-paths)}]
                args
                {:prog "bb-unused-vars" :help true}))
