(ns cldwalker.babashka.util
  "Util fns for babashka scripts"
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.cli :as cli]))

;; Misc
;; ====
(defn open-url
  "Osx specific way to open url in browser"
  [url]
  (shell/sh "open" url))

;; CLI
;; ===
(defn error
  "Print error message(s) and exit"
  [& msgs]
  (apply println "Error:" msgs)
  (System/exit 1))

(defn print-summary
  "Print help summary given args and opts strings"
  [args-string options-summary]
  (println (format "Usage: %s [OPTIONS] %s\nOptions:\n%s"
                   (.getName (io/file *file*))
                   args-string
                   options-summary)))

(defn run-command [command-fn args cli-opts]
  (let [{:keys [errors] :as parsed-input}
        (cli/parse-opts args cli-opts)]
    (if (seq errors)
      (error (str/join "\n" (into ["Options failed to parse:"] errors)))
      (command-fn parsed-input))))

;; Github
;; ======
(defn find-current-user-repo
  "Returns github user/repository of current directory"
  [opts]
  ;; Don't like this knowing about help but don't want error scenarios occurring
  ;; when running --help
  (if (:help opts)
    opts
    (let [{:keys [out exit err]} (shell/sh "git" "config" "remote.origin.url")]
      (if (zero? exit)
        ;; Can handle gh:atom/atom, https://github.com/atom/atom.git or git@github.com:atom/atom.git
        (if-let [user-repo (second (re-find #"(?:gh|github.com)(?::|/)([^/]+/[^/.\s]+)" out))]
          user-repo
          (error "Failed to determine current directory's repository" (pr-str {:out out})))
        (error "Failed to determine current directory's repository" (pr-str {:error err :out out}))))))
