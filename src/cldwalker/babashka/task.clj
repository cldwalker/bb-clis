(ns cldwalker.babashka.task
  "Provides helpers for babashka tasks"
  (:require [babashka.tasks :refer [current-task run]]
            [clojure.edn :as edn]))

;; Utilities
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

;; Reusable tasks
(def repl-task
  {:doc "Pull up socket repl with #'task/result bound to result of given task and args"
   :usage "TASK [& ARGS]"
   :task-fn
   (fn []
     (let [task (symbol (first *command-line-args*))]
      (binding [*command-line-args* (rest *command-line-args*)]
        ;; Assumes task stdout is edn
        (def result (edn/read-string (with-out-str (run task)))))
      ;; Used to use clojure.main/repl but this allows for in-editor repl
      ((requiring-resolve 'clojure.core.server/start-server)
       {:port 5555
        :name "bb-task"
        :accept 'clojure.core.server/repl})
      (clojure.core.server/repl)))})

;; Evaluate any task result in editor
(comment
 (-> result)
 )
