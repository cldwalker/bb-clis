(ns cldwalker.bb-clis.bin.logseq-bookmark
  "Bookmark a URL by upserting a block with url/description properties and a tag."
  (:require [babashka.http-client :as http]
            [babashka.tasks :refer [shell]]
            [cldwalker.bb-clis.cli :as cli]
            [clojure.string :as str]))

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

(defn- command [{:keys [options arguments summary]}]
  (cond
    (or (:help options)
        (not= 1 (count arguments)))
    (cli/print-summary " URL" summary)

    :else
    (let [[url] arguments
          {:keys [graph description deadline referrerURL tag content]} options
          properties (cond-> {"url" url}
                       description (assoc "description" description)
                       deadline (assoc "deadline" deadline)
                       referrerURL (assoc "referrerURL" referrerURL))]
      (shell "logseq" "upsert" "block"
             "-g" graph
             "-c" (or content (fetch-title url) "Untitled")
             "--update-properties" (pr-str properties)
             "--update-tags" (pr-str [tag])))))

(def ^:private cli-options
  [["-h" "--help"]
   ["-g" "--graph GRAPH" "Graph name" :default "personal"]
   ["-d" "--description DESCRIPTION" "Block description property"]
   ["-D" "--deadline DEADLINE" "Deadline for a task"]
   ["-t" "--tag TAG" "Tag to add" :default "Task"]
   ["-r" "--referrerURL URL"]
   ["-c" "--content CONTENT" "Block content (default: fetched <title> or \"Untitled\")"]])

(defn -main [& args]
  (cli/run-command command args cli-options))
