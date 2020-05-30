(ns cldwalker.babashka.util
  "Util fns for babashka scripts"
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.tools.cli :as cli]))

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

;; Misc
;; ====
(defn open-url
  "Osx specific way to open url in browser"
  [url]
  (shell/sh "open" url))


(defn sh
  "Wrapper around a shell command which fails fast like bash's -e flag.
Takes following options:
* :dry-run: Prints command instead of executing it"
  [& args*]
  (let [default-opts {:is-error-fn #(-> % :exit (not= 0))}
        [options args] (if (map? (last args*))
                         [(merge default-opts (last args*))
                          (drop-last args*)]
                         [default-opts args*])]
    (if (:dry-run options)
      (apply println "Command: " args)
      (let [{:keys [out err] :as res}
            (apply shell/sh
              (concat args [:dir (:dir options)]))]
        (if ((:is-error-fn options) res)
          (error (format "Command '%s' failed with:\n%s"
                         (str/join " " args)
                         (str out "\n" err)))
          out)))))

(defn exec
  "Hands over current process to new command. Similar to exec in shell script.
Thanks to https://github.com/borkdude/babashka/issues/299"
  [cmd-and-args]
  (let [pb (doto (ProcessBuilder. cmd-and-args)
                 (.inheritIO))
        p (.start pb)]
    (System/exit (.waitFor p))))

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

;; I/O
;; ===

(defn read-stdin-edn
  "Read edn on *in* into a coll. Useful to convert a `bb -I --stream` cmd to
a script that doesn't require that invocation."
  []
  (take-while #(not (identical? ::EOF %))
              (repeatedly #(edn/read {:eof ::EOF} *in*))))

(defn stdin-active?
  "This can be used to detect if stdin is active. Limitations to this
are noted in https://github.com/borkdude/babashka/issues/324#issuecomment-621631592"
  []
  (pos? (.available System/in)))
