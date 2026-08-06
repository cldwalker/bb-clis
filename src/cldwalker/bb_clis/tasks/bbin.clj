(ns cldwalker.bb-clis.tasks.bbin
  "bbin related tasks"
  (:require [babashka.fs :as fs]
            [babashka.process :as process :refer [shell]]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- config-file
  "The running task's bb.edn, resolved from the `babashka.config` property so
  tasks and completions work from any directory e.g. via the bbg fn."
  []
  (str (fs/absolutize (System/getProperty "babashka.config"))))

(defn- bbin-entries
  []
  (:bbin/bin (edn/read-string (slurp (config-file)))))

(defn- complete-cmds
  "Completion candidates for `:cmds` args - the `:bbin/bin` entry names."
  [_m]
  (sort (map str (keys (bbin-entries)))))

(defn uninstall
  "Uninstall every :bbin/bin entry from current bb.edn"
  [_options]
  (let [entries (bbin-entries)]
    (when (empty? entries)
      (println "No :bbin/bin entries found in bb.edn")
      (System/exit 1))
    (doseq [[name _] entries]
      (shell "bbin" "uninstall" name))))

(defn install
  "Install every :bbin/bin entry from current bb.edn by default.
  Optional commands will only install those commands.
  Installs with `--local/root` so edits to src/ are picked up live without reinstalling."
  {:org.babashka/cli {:spec {:cmds {:desc "Commands to install" :coerce [] :positional true
                                    :complete-fn complete-cmds}}
                      :args->opts (repeat :cmds)}}
  [{:keys [cmds]}]
  (let [root (str (fs/parent (config-file)))
        entries* (bbin-entries)
        entries (if (seq cmds) (select-keys entries* (map symbol cmds)) entries*)]
    (when (empty? entries)
      (println "No :bbin/bin entries found in bb.edn")
      (System/exit 1))
    (doseq [[name {:keys [main-opts]}] entries]
      (println "Installing" (str name))
      (shell "bbin" "install" root
             "--as" (str name)
             "--main-opts" (pr-str main-opts)))))

(defn build-completions
  "Regenerate zsh completion files from bb for bbin CLI's
  `completion-cmds` by invoking its babashka.cli completions snippet."
  {:org.babashka/cli {:spec {:cmds {:desc "Commands to build completions for" :coerce [] :positional true
                                    :complete-fn complete-cmds}}
                      :args->opts (repeat :cmds)}}
  [{:keys [cmds]}]
  (let [completions-dir (str (fs/path (fs/home) ".zsh" "completions"))
        entries* (bbin-entries)
        completion-cmds (->> (if (seq cmds) (select-keys entries* (map symbol cmds)) entries*)
                             keys
                             (map str))]
    (fs/create-dirs completions-dir)
    (doseq [cmd completion-cmds]
      (let [out-file (str (fs/path completions-dir (str "_" cmd)))
            {:keys [out exit]} (shell {:out :string}
                                      cmd "org.babashka.cli/completions"
                                      "snippet" "--shell" "zsh")]
        (if (and (zero? exit) (str/starts-with? out "#compdef"))
          (do (spit out-file out)
              (println "Wrote" out-file))
          (println "Skipping" cmd "as it doesn't use babashka.cli"))))))
