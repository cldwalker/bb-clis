(ns cldwalker.bb-clis.tasks.git
  (:require [clojure.tools.gitlibs :as gl]))

(def clone-cli-options
  [["-b" "--branch BRANCH" :default "master"]
   ["-n" "--name NAME"]])

(defn clone
  [parsed-args]
  (let [{:keys [options arguments]} parsed-args
        url (first arguments)
        gitlib-name (or (some-> (:name options) symbol)
                        ;; Try parsing a sensible name from a github.com/repo/name like url
                        (let [[_ namespace name] (re-find (re-pattern "([^/]+)/([^/]+)$") url)]
                          (when (nil? namespace)
                            (throw (ex-info "name not detected from a url. Please specify --name" {})))
                          (symbol (str "gh." namespace) name)))
        dir (gl/procure url gitlib-name (:branch options))]
    (println "Cloned to" dir)
    dir))
