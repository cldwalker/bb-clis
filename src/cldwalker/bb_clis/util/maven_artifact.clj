(ns ^:no-doc cldwalker.bb-clis.util.maven-artifact
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :as shell]))

(defn artifact-files
  "List clj/cljs/cljc files in a maven artifact. When orienting yourself
in a new library, this fn and (dir) are helpful for quick api exploration."
  [artifact version]
  (let [artifact_ (if (.contains artifact "/")
                    artifact
                    (str artifact "/" artifact))]
    (if-let [jar-file (->> (format "%s/.m2/repository/%s/%s"
                                   (System/getenv "HOME") artifact_ version)
                           io/file
                           .listFiles
                           seq
                           (filter #(re-find #"\.jar$" (str %)))
                           first)]
      (->> (str/split-lines (:out (shell/sh "jar" "-tf" (str jar-file))))
           (filter #(re-find #"\.(clj|cljs|cljc)$" %))
           (remove #(re-find #"project.clj$" %)))
      (println "No jar file found"))))
