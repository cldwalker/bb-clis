(ns cldwalker.bb-clis.bin.logseq-graph-grep
  "Recursively grep a logseq graph directory."
  (:require [babashka.cli :as cli]
            [babashka.tasks :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(defn- current-graph []
  (-> (shell {:out :string :continue true} "zsh" "-ic" "_logseq_current_graph")
      :out
      str/trim
      not-empty))

(def ^:private spec
  {:graph {:alias :g :desc "Graph to grep (default: current graph)"}})

(defn- run-grep [{:keys [graph]} grep-args]
  (let [graph* (or graph
                   (current-graph)
                   (cli-util/error "Could not determine current graph"))
        dir (str (System/getenv "HOME") "/logseq/graphs/" graph* "/mirror/markdown")
        {:keys [exit]} (apply shell {:continue true :dir dir}
                              "grep" "-r" (concat grep-args ["."]))]
    (System/exit exit)))

(defn- print-help []
  (println (str "Usage: logseq-graph-grep [options] [& grep-args]\n\n"
                "Options:\n"
                (cli/format-opts {:spec (assoc spec :help {:alias :h :coerce :boolean :desc "Show this help"})}))))

(defn- command
  "Ignores dispatch's parsed input in favor of raw args so unrecognized
  options pass through to grep"
  [args]
  (let [[our-args grep-args] (cli-util/split-leading-opts spec args)
        opts (:opts (cli/parse-args our-args {:spec spec}))]
    (run-grep opts grep-args)))

(defn -main [& args]
  ;; Dispatch is only used for -h and to provide org.babashka.cli/completions
  (cli/dispatch [{:cmds [] :spec spec :fn (fn [_] (command args))}]
                args
                {:prog "logseq-graph-grep" :help true :help-fn (fn [_] (print-help))}))
