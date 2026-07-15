(ns cldwalker.bb-clis.bin.bb-missed-docstrings
  "A slightly modified version of https://github.com/borkdude/clj-kondo/blob/master/analysis/src/clj_kondo/tools/missing_docstrings.clj"
  (:require [babashka.cli :as cli]
            [babashka.pods :as pods]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(pods/load-pod "clj-kondo")
(require '[pod.borkdude.clj-kondo :as clj-kondo])

(defn- missed-docstrings [paths {:keys [ignore-regex]}]
  (let [analysis (:analysis (clj-kondo/run! {:lint paths
                                             :config {:output {:analysis true}}}))
        {:keys [var-definitions]} analysis
        ignore-var? (if ignore-regex
                      #(re-find (re-pattern ignore-regex) %)
                      (constantly false))]
    (doseq [{:keys [ns name private doc]} var-definitions]
      (when (and (not private)
                 (not doc)
                 (not (ignore-var? (str name))))
        (println (str ns "/" name ": missing docstring"))))))

(defn- command [{:keys [opts]}]
  (missed-docstrings (:source-paths opts) opts))

(def ^:private spec
  {:source-paths {:positional true :coerce [:string] :require true :desc "Source paths to analyze"}
   :ignore-regex {:alias :i :desc "Ignore vars matching regex"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec :args->opts (repeat :source-paths)}]
                args
                {:prog "bb-missed-docstrings" :help true}))
