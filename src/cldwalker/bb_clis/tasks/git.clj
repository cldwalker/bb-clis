(ns cldwalker.bb-clis.tasks.git
  (:require [clojure.tools.gitlibs :as gl]))

(defn clone
  "Clone a git url."
  {:org.babashka/cli {:spec {:url {:desc "Git url to clone" :positional true}
                             :branch {:alias :b
                                      :default "master"
                                      :desc "Branch to clone"}
                             :name {:alias :n
                                    :desc "Local name for cloned library"}}
                      :args->opts [:url]
                      :require [:url]}}
  [{:keys [url] :as options}]
  (let [gitlib-name (or (some-> (:name options) symbol)
                        ;; Try parsing a sensible name from a github.com/repo/name like url
                        (let [[_ namespace name] (re-find (re-pattern "([^/]+)/([^/]+)$") url)]
                          (when (nil? namespace)
                            (throw (ex-info "name not detected from a url. Please specify --name" {})))
                          (symbol (str "gh." namespace) name)))
        dir (gl/procure url gitlib-name (:branch options))]
    (println "Cloned to" dir)
    dir))
