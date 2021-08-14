(ns cldwalker.bb-clis.cli.misc
  "Misc fns that are useful to bb/clojure clis"
  (:require [cldwalker.bb-clis.cli :as cli]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.shell :as shell]))

;; Misc
;; ====
(defn open-url
  "Osx specific way to open url in browser"
  [url]
  ;; -n needed to open in big sur.
  ;; See https://apple.stackexchange.com/questions/406556/macos-big-sur-terminal-command-open-behaviour-changed-and-i-dont-know-how-to
  (shell/sh "open" "-n" url))


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
          (cli/error (format "Command '%s' failed with:\n%s"
                             (str/join " " args)
                             (str out "\n" err)))
          out)))))

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
          (cli/error "Failed to determine current directory's repository" (pr-str {:out out})))
        (cli/error "Failed to determine current directory's repository" (pr-str {:error err :out out}))))))

;; I/O
;; ===

(defn read-stdin-edn
  "Read edn on *in* into a coll. Useful to convert a `bb -I --stream` cmd to
a script that doesn't require that invocation."
  []
  (take-while #(not (identical? ::EOF %))
              (repeatedly #(edn/read {:eof ::EOF} *in*))))

(defn stdin-active?
  "Detect if stdin is active. Waits for a split second"
  []
  ;; Sleep needed per https://github.com/babashka/babashka/issues/324#issuecomment-639810098
  (Thread/sleep 100)
  (pos? (.available System/in)))
