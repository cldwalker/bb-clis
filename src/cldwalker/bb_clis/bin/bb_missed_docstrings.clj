(ns cldwalker.bb-clis.bin.bb-missed-docstrings
  "A slightly modified version of https://github.com/borkdude/clj-kondo/blob/master/analysis/src/clj_kondo/tools/missing_docstrings.clj"
  (:require [cldwalker.bb-clis.cli :as cli]
            [babashka.pods :as pods]))

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

(defn- command [{:keys [arguments summary options]}]
  (if (:help options)
    (cli/print-summary " SOURCE-PATHS" summary)
    (missed-docstrings arguments options)))

(def ^:private cli-options
  [["-h" "--help"]
   ["-i" "--ignore-regex REGEX"]])

(defn -main [& args]
  (cli/run-command command args cli-options))
