(ns cldwalker.bb-clis.tasks.bb
  "Tasks for the bb CLI itself"
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [clojure.string :as str]))

(defn- bbg-snippet
  "Derives a zsh completion snippet for the bbg fn from bb's snippet. bbg runs
  this repo's tasks from anywhere by pointing bb at this repo's bb.edn."
  [bb-snippet]
  (let [config (str (fs/absolutize (System/getProperty "babashka.config")))
        home (str (fs/home))
        ;; Write paths with ~ so the snippet isn't tied to a specific home dir
        config (if (str/starts-with? config (str home "/"))
                 (str "~" (subs config (count home)))
                 config)
        bbg-invocation (format "BB_EDN=%s bb --config %s" config config)]
    (-> bb-snippet
        (str/replace-first "#compdef bb\n" "#compdef bbg\n")
        (str/replace "_babashka_cli_complete_bb" "_babashka_cli_complete_bbg")
        (str/replace "\"${words[1]}\" org.babashka.cli/completions"
                     (str bbg-invocation " org.babashka.cli/completions"))
        (str/replace ":bb:" ":bbg:")
        (str/replace "compdef _babashka_cli_complete_bbg bb\n"
                     "compdef _babashka_cli_complete_bbg bbg\n"))))

(defn build-completions
  "Regenerate zsh completion files for bb and for the bbg fn that runs this
  repo's tasks from anywhere."
  [_options]
  (let [completions-dir (fs/path (fs/home) ".zsh" "completions")
        bb-snippet (:out (shell {:out :string}
                                "bb" "org.babashka.cli/completions"
                                "snippet" "--shell" "zsh"))]
    (fs/create-dirs completions-dir)
    (doseq [[file snippet] [["_bb" bb-snippet]
                            ["_bbg" (bbg-snippet bb-snippet)]]]
      (let [out-file (str (fs/path completions-dir file))]
        (spit out-file snippet)
        (println "Wrote" out-file)))))
