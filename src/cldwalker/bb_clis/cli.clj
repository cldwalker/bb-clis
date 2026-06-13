(ns cldwalker.bb-clis.cli
  "Common fns for babashka/clojure CLIs"
  (:require [clojure.string :as str]
            [clojure.tools.cli :as cli]))

(defn error
  "Print error message(s) and exit"
  [& msgs]
  (apply println "Error:" msgs)
  (System/exit 1))

(defmacro print-summary
  "Print help summary given args and opts strings. Program name is the last
  segment of the caller's namespace (e.g. cldwalker.bb-clis.bin.bb-table → bb-table)."
  [args-string options-summary]
  (let [program-name (last (str/split (str (ns-name *ns*)) #"\."))]
    `(println (format "Usage: %s [OPTIONS]%s\nOptions:\n%s"
                      ~program-name
                      ~args-string
                      ~options-summary))))

(defn run-command
  "Processes a command's functionality given a cli options definition, arguments
  and primary command fn. This handles option parsing, handles any errors with
  parsing and then passes parsed input to command fn"
  [command-fn args cli-opts & parse-opts-options]
  (let [{:keys [errors] :as parsed-input}
        (apply cli/parse-opts args cli-opts parse-opts-options)]
    (if (seq errors)
      (error (str/join "\n" (into ["Options failed to parse:"] errors)))
      (command-fn parsed-input))))
