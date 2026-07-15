(ns cldwalker.bb-clis.bin.logseq-bookmark
  "Bookmark a URL by upserting a block with url/description properties and a tag."
  (:require [babashka.cli :as cli]
            [babashka.http-client :as http]
            [babashka.tasks :refer [shell]]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(defn- fetch-title [url]
  (try
    (let [body (:body (http/get url {:timeout 5000}))
          [_ title] (re-find #"(?is)<title[^>]*>(.*?)</title>" (or body ""))]
      (println "Fetched title:" (pr-str title))
      (if title
        (str/trim (str/replace title #"\s+" " "))
        (do (println "Unable to find url's title") nil)))
    (catch java.net.http.HttpTimeoutException _
      (println "Timed out while fetching url for title")
      nil)
    (catch Exception _
      (println "Unable to find url's title")
      nil)))

(defn- command [{:keys [opts]}]
  (let [{:keys [url graph description deadline referrerURL tag content]} opts
        properties (cond-> {"url" url}
                     description (assoc "description" description)
                     deadline (assoc "deadline" deadline)
                     referrerURL (assoc "referrerURL" referrerURL))]
    (shell "logseq" "upsert" "block"
           "-g" graph
           "-c" (or content (fetch-title url) "Untitled")
           "--update-properties" (pr-str properties)
           "--update-tags" (pr-str [tag]))))

(def ^:private spec
  {:url {:positional true :coerce :string :desc "URL to bookmark" :require true}
   :graph {:alias :g :desc "Graph name" :default "personal"}
   :description {:alias :d :desc "Description for node"}
   :deadline {:alias :D :desc "Deadline for node"}
   :tag {:alias :t :desc "Tag for node" :default "Task"}
   :referrerURL {:alias :r :desc "Referrer URL for node"}
   :content {:alias :c :desc "Block content (default: fetched <title> or \"Untitled\")"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec :args->opts [:url] :restrict-args true}]
                args
                {:prog "logseq-bookmark" :help true}))
