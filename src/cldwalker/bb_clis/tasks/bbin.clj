(ns cldwalker.bb-clis.tasks.bbin
  "bbin related tasks"
  (:require [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]
            [clojure.edn :as edn]))

(defn uninstall
  "Uninstall every :bbin/bin entry from current bb.edn"
  []
  (let [root (str (fs/cwd))
        entries (:bbin/bin (edn/read-string (slurp (str (fs/path root "bb.edn")))))]
    (when (empty? entries)
      (println "No :bbin/bin entries found in bb.edn")
      (System/exit 1))
    (doseq [[name _] entries]
      (shell "bbin" "uninstall" name))))

(defn install
  "Install every :bbin/bin entry from current bb.edn. Installs with
  `--local/root` so edits to src/ are picked up live without reinstalling."
  []
  (let [root (str (fs/cwd))
        entries (:bbin/bin (edn/read-string (slurp (str (fs/path root "bb.edn")))))]
    (when (empty? entries)
      (println "No :bbin/bin entries found in bb.edn")
      (System/exit 1))
    (doseq [[name {:keys [main-opts]}] entries]
      (println "Installing" (str name))
      (shell "bbin" "install" root
             "--as" (str name)
             "--main-opts" (pr-str main-opts)))))
