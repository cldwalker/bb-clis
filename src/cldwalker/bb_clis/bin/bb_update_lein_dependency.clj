(ns cldwalker.bb-clis.bin.bb-update-lein-dependency
  "Updates a lein dependency on specified git-able dir(s)"
  (:require [babashka.cli :as cli]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(defn- sh
  "Wrapper around a shell command which fails fast like bash's -e flag.
Takes following options:
* :dry-run: Prints command instead of executing it"
  [& args*]
  (let [[options args] (if (map? (last args*))
                         [(last args*) (drop-last args*)]
                         [{} args*])]
    (if (:dry-run options)
      (apply println "Command: " args)
      (let [{:keys [out err exit]} (apply shell/sh
                                     (concat args [:dir (:dir options)]))]
        (if (zero? exit)
          out
          (cli-util/error (format "Command '%s' failed with:\n%s"
                                  (str/join " " args)
                                  (str out "\n" err))))))))

(defn- update-project-clj
  "Updates project.clj and fails fast if unable to update"
  [file dependency version {:keys [dry-run]}]
  (if dry-run
    (println "Command: update-project-clj" file dependency version)
    (let [body (slurp file)
          new-body (str/replace-first body
                                      (re-pattern (format "(%s\\s+\")(\\w+)(\")" dependency))
                                      (format "$1%s$3" version))]
      (if (= body new-body)
        (cli-util/error (str "Unable to find and update dependency in " file))
        (spit file new-body)))))

(defn- update-dir
  "Updates project.clj in dir and optionally git commits and pushes"
  [dependency version dir {:keys [commit-and-push] :as options*}]
  (println (str "Updating " dir " ..."))

  (let [options (assoc options* :dir dir)]
    (sh "git" "diff" "--exit-code" options)
    (update-project-clj (str (io/file dir "project.clj"))
                        dependency
                        version
                        options)

    (when commit-and-push
      (sh "git" "commit" "-m" (format "Updated %s dependency" dependency) "." options)
      (sh "git" "push" options))))

(def ^:private spec
  {:dependency {:positional true :coerce :string :desc "Lein dependency to update" :require true}
   ;; :coerce :string prevents versions like "1.10" auto-coercing to the number 1.1
   :version {:positional true :coerce :string :desc "New version" :require true}
   :directories {:alias :d :coerce [:string]
                 :default [(System/getenv "PWD")]
                 :default-desc "Current directory"
                 :validate {:pred #(every? (fn [d] (.isDirectory (io/file d ".git"))) %)
                            :ex-msg (constantly "Must be a valid git directory")}
                 :desc "Directories to update"}
   :commit-and-push {:alias :c :coerce :boolean :desc "Git commit and push to remote"}
   :dry-run {:alias :n :coerce :boolean :desc "Actions are printed and not executed"}})

(defn- command [{:keys [opts]}]
  (let [{:keys [dependency version directories]} opts]
    (doseq [dir directories]
      (update-dir dependency version dir opts))))

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec :args->opts [:dependency :version] :restrict-args true}]
                args
                {:prog "bb-update-lein-dependency" :help true}))
