(ns cldwalker.bb-clis.bin.gh-pr-for-commit
  "Fetch and open info related to a github PR"
  (:require [babashka.cli :as cli]
            [babashka.curl :as curl]
            [cheshire.core :as json]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.cli.misc :as misc]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(defn- fetch-response* [commit {:keys [repository token user]}]
  (try
    (curl/get (format "https://api.github.com/repos/%s/commits/%s/pulls"
                      repository commit)
              ;; Check https://developer.github.com/v3/repos/commits/#list-pull-requests-associated-with-commit to see if this is still in preview
              (cond-> {:headers {"Accept" "application/vnd.github.groot-preview+json"}}
                      (and user token) (assoc :basic-auth [user token])))
    (catch clojure.lang.ExceptionInfo e
      (cli-util/error "Failed to fetch github information" (pr-str {:error (ex-message e)})))))

(defn- fetch-response [commit options]
  (let [{:keys [body]} (fetch-response* commit options)
        repos (json/parse-string body true)]
    (if-let [url (if (= 1 (count repos))
                   (-> repos first :html_url)
                   (let [repos' (remove #(get-in % [:base :repo :fork]) repos)]
                     (prn :URLS (map :html_url repos'))
                     (->> repos' (filter :merged_at) first :html_url)))]
      (do (misc/open-url url)
          url)
      (cli-util/error "No github PR found for this commit"))))

(defn- command [{:keys [opts]}]
  ;; Applied lazily here since it shells out and errors outside a git repo
  (let [opts (update opts :repository #(or % (misc/find-current-user-repo opts)))]
    (when (:debug opts) (println "Options:" opts))
    (fetch-response (:commit opts) opts)))

(def ^:private spec
  ;; :coerce :string prevents an all-digit sha auto-coercing to a number
  {:commit {:positional true :coerce :string :desc "Git commit sha" :require true}
   :repository {:alias :r
                :default-desc "Current directory's repository"
                :validate {:pred #(re-find #"\S+/\S+" %) :ex-msg (constantly "Must contain a '/'")}
                :desc "Github repository"}
   :debug {:alias :d :coerce :boolean :desc "Print debug info"}
   :user {:alias :u
          :default (System/getenv "GITHUB_USER")
          :default-desc "$GITHUB_USER"
          :desc "Github user"}
   :token {:alias :t
           :default (System/getenv "GITHUB_OAUTH_TOKEN")
           :default-desc "$GITHUB_OAUTH_TOKEN"
           :desc "Github OAuth token"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec :args->opts [:commit] :restrict-args true}]
                args
                {:prog "gh-pr-for-commit" :help true}))
