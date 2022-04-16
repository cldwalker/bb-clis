(ns cldwalker.bb-clis.tasks.nbb
  "Tasks related to nbb"
  (:require [babashka.fs :as fs]
            [babashka.tasks :refer [shell]]))

(defn write-deps
  "Write dependencies in deps folder"
  []
  (shell "bb --config nbb.edn uberjar nbb-deps.jar")
  (fs/create-dirs ".nbb-deps")
  (fs/unzip "nbb-deps.jar" ".nbb-deps")
  (fs/delete "nbb-deps.jar"))
