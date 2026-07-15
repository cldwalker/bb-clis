(ns cldwalker.bb-clis.bin.bb-try
  "Try a dependency with bb like lein-try"
  (:require [babashka.cli :as cli]
            [babashka.tasks :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.java.shell :as shell]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(def ^:private spec
  {:command {:alias :c :desc "Command to run (bb or clojure)"}
   :version {:alias :v :desc "Dependency version"}
   ;; print-command only works for commands without env var e.g. clj
   :print-command {:alias :p :coerce :boolean :desc "Print command instead of running it"}})

(defn- create-deps-string
  [dependency {:keys [version] :or {version "RELEASE"}}]
  (format "{:deps {%s {:mvn/version \"%s\"}}}"
          dependency version))

(defn- exec [args env print-command?]
  ;; print-command doesn't work for bb b/c it can't print env var in a way that's
  ;; reproducible for others
  (if print-command?
    (apply println (map #(if (.contains % " ")
                           (str "'" % "'")
                           %)
                        args))
    (do (apply shell {:extra-env env} args)
      nil)))

(defn- bb-main
  [dependency bb-args {:keys [command print-command] :as options}]
  (let [deps-string (create-deps-string dependency options)
        new-classpath (str (System/getenv "BABASHKA_CLASSPATH")
                           ":"
                           (:out (shell {:out :string} "clojure" "-Spath" "-Sdeps" deps-string)))
        bb-invocation (if (-> (shell/sh "which" "rlwrap") :exit zero?)
                        ["rlwrap" command]
                        [command])]
    (exec (concat bb-invocation bb-args)
          {"BABASHKA_CLASSPATH" new-classpath}
          print-command)))

(defn- clj-main [dependency clj-args {:keys [print-command] :as options}]
  (let [deps-string (create-deps-string dependency options)
        clj-options ["-Sdeps" deps-string]
        clj-invocation (if (-> (shell/sh "which" "rlwrap") :exit zero?)
                         ["rlwrap" "clojure"]
                         ["clojure"])]
    (exec (concat clj-invocation clj-options clj-args)
          {}
          print-command)))

(defn- print-help []
  (println (str "Usage: bb-try [options] <dependency> [& args]\n\n"
                "Options:\n"
                (cli/format-opts {:spec (assoc spec :help {:alias :h :coerce :boolean :desc "Show this help"})}))))

(defn- command*
  "Ignores dispatch's parsed input in favor of raw args so unrecognized
  options pass through to the tried command"
  [args]
  (if (empty? args)
    (print-help)
    (let [[our-args rest-args] (cli-util/split-leading-opts spec args)
          opts (:opts (cli/parse-args our-args {:spec spec}))
          [dependency & extra] rest-args
          command (:command opts "bb")]
      (if (#{"clj" "clojure"} command)
        (clj-main dependency extra opts)
        (bb-main dependency extra (assoc opts :command command))))))

(defn -main [& args]
  ;; Dispatch is only used for -h and to provide org.babashka.cli/completions
  (cli/dispatch [{:cmds [] :spec spec :fn (fn [_] (command* args))}]
                args
                {:prog "bb-try" :help true :help-fn (fn [_] (print-help))}))
