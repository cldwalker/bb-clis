(ns cldwalker.babashka.task
  "Provides helpers for babashka tasks"
  (:require [babashka.tasks :refer [current-task]]))

(defn parse-options
  "Provides :options for a task to define options for cli/parse-opts. Use
  :cli-options for additional arguments to cli/parse-opts. If no :options,
  return ::no-options."
  []
  ;; Unsupported task keys like :options don't return values with valid
  ;; fns. Eval translates symbols to fns. Could reach into bb internals
  ;; or ask for this to be supported upstream but this is least effort
  ;; and coupling.
  (if-let [task-opts (some-> (:options (current-task)) eval)]
    (let [parsed-args
          (apply (requiring-resolve 'clojure.tools.cli/parse-opts) *command-line-args*
            task-opts
            (:cli-options (current-task)))]
      parsed-args)
    ::no-options))
