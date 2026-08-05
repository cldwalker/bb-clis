(ns cldwalker.bb-clis.tasks.ns-in-dir
  "Task for printing namespaces in a dir"
  (:require [clojure.tools.namespace.find :as find]
            [babashka.fs :as fs]))

(defn ns-in-dir
  "Prints namespaces in dir"
  {:org.babashka/cli {:spec {:dir {:desc "Directory to search" :positional true}}
                      :args->opts [:dir]
                      :require [:dir]}}
  [{:keys [dir]}]
  (prn (find/find-namespaces-in-dir (fs/file dir))))
