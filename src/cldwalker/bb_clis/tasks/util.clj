(ns cldwalker.bb-clis.tasks.util
  (:require [clojure.string :as str]))

(defn parse-options
  "Provides :options for a task to define options for cli/parse-opts. Use
  :cli-options for additional arguments to cli/parse-opts. If no :options,
  return ::no-options."
  [task-map]
  ;; Unsupported task keys like :options don't return values with valid
  ;; fns. Eval translates symbols to fns. Could reach into bb internals
  ;; or ask for this to be supported upstream but this is least effort
  ;; and coupling.
  (if-let [task-opts (some-> (:options task-map) eval)]
    (let [parsed-args
          (apply (requiring-resolve 'clojure.tools.cli/parse-opts) *command-line-args*
            task-opts
            (:cli-options task-map))]
      parsed-args)
    ::no-options))

(defn check-for-required-arguments [parsed-args task-map]
  (let [required-args (->> (str (:usage task-map))
                           ((fn [s] (str/split s (re-pattern "\\s+"))))
                           (take-while (fn [s] (re-find (re-pattern "^[A-Z]") s))))
        args (if (= ::no-options parsed-args)
               *command-line-args* (:arguments parsed-args))]
    (when (< (count args) (count required-args))
      (println "Wrong number of arguments given.")
      (println (format "Usage: bb %s %s%s"
                       (:name task-map)
                       (:usage task-map)
                       (if-let [summary (:summary parsed-args)]
                         (str "\nOptions:\n" summary)
                         "")))
      (System/exit 1))))
