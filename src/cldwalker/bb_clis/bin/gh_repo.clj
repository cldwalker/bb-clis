(ns cldwalker.bb-clis.bin.gh-repo
  "Opens urls related to current or specified github repository"
  (:require [babashka.cli :as cli]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.cli.misc :as misc]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(defn- find-current-branch []
  (if-let [branch (->> (shell/sh "git" "branch")
                       :out
                       str/split-lines
                       (filter #(str/starts-with? % "*"))
                       first
                       (re-find #"\S+$"))]
    branch
    (cli-util/error "Unable to detect current tree")))

(defn- get-relative-git-root-path
  "Given a path, return its path relative to git root"
  [path]
  (let [top-level-dir (-> (shell/sh "git" "rev-parse" "--show-toplevel")
                          :out
                          str/trimr
                          (str "/"))
        full-path (.getAbsolutePath (io/file path))
        git-relative-path (str/replace-first full-path top-level-dir "")]
    git-relative-path))

(defn- open-github-url [{:keys [repository commit tree file]}]
  (let [wiki-repository (second (re-matches #"(\S+)\.wiki" repository))
        url-base (str "https://github.com/" (or wiki-repository repository))
        url (cond
              commit (str url-base "/commit/" commit)
              tree   (str url-base "/tree/" (find-current-branch))
              (and wiki-repository file)
              (str url-base "/wiki/"
                   (str/replace-first (get-relative-git-root-path file) #"\.[^./]+$" ""))
              file   (str url-base "/blob/" (find-current-branch)
                          "/" (get-relative-git-root-path file))
              wiki-repository (str url-base "/wiki")
              :else url-base)]
    (doto url misc/open-url)))

(defn- open-circleci-url [{:keys [repository]}]
  (let [url (format "https://circleci.com/gh/%s/tree/%s"
                    repository
                    (find-current-branch))]
    (doto url misc/open-url)))

(defn- command [{:keys [opts]}]
  ;; Applied lazily here since it shells out and errors outside a git repo
  (let [opts (update opts :repository #(or % (misc/find-current-user-repo opts)))]
    (if (:circleci opts)
      (open-circleci-url opts)
      (open-github-url opts))))

(def ^:private spec
  {:repository {:alias :r
                :default-desc "Current directory's repository"
                :validate {:pred #(re-find #"\S+/\S+" %) :ex-msg (constantly "Must contain a '/'")}
                :desc "Github repository"}
   :commit {:alias :c :desc "Opens specified commit"}
   :circleci {:alias :C :coerce :boolean :desc "Opens current branch on circleci"}
   :tree {:alias :t :coerce :boolean :desc "Opens current branch on github"}
   :file {:alias :f :desc "Opens file in current branch on github"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec}]
                args
                {:prog "gh-repo" :help true}))
