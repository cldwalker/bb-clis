(ns cldwalker.bb-clis.bin.logseq-identify-pages
  "Identify Logseq pages by resolving each to a Wikipedia page, then save the
  chosen urls as the page's url property."
  (:require [babashka.cli :as cli]
            [babashka.http-client :as http]
            [babashka.process :refer [shell]]
            [cheshire.core :as json]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.input :as input]
            [cldwalker.bb-clis.util.logseq :as logseq-util]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.net URLEncoder]))

(defn- graph-args [graph]
  (when graph ["-g" graph]))

(defn- logseq-query [graph query inputs]
  (let [{:keys [out]} (apply shell {:out :string :continue true} "logseq" "query"
                             (concat (graph-args graph)
                                     ["-o" "edn" "--query" (pr-str query)]
                                     (when inputs ["--inputs" (pr-str inputs)])))
        {:keys [status data error]} (edn/read-string out)]
    (when (not= :ok status)
      (cli-util/error "Query failed:" (or (:message error) (pr-str error) out)))
    (:result data)))

(defn- names->ids
  "Resolves each page name to its id as `{name id}`, erroring if any name matches
  zero or more than one page."
  [graph names]
  (if (empty? names)
    {}
    (let [rows (logseq-query graph
                              '[:find ?title ?e
                                :in $ [?title ...]
                                :where [?e :block/title ?title] [?e :block/name]]
                              [(vec names)])
          by-title (group-by first rows)
          missing (remove by-title names)
          non-unique (filter #(> (count (by-title %)) 1) names)]
      (when (seq missing)
        (cli-util/error "No page found named:" (str/join ", " missing)))
      (when (seq non-unique)
        (cli-util/error "Multiple pages found named:" (str/join ", " non-unique)))
      (into {} (map (fn [title] [title (second (first (by-title title)))])) names))))

(defn- ids->names
  "Resolves each id's :block/title as its name, returned as `{name id}`, erroring
  if any id doesn't exist."
  [graph ids]
  (if (empty? ids)
    {}
    (let [e->title (into {} (logseq-query graph
                                          '[:find ?e ?title
                                            :in $ [?e ...]
                                            :where [?e :block/title ?title]]
                                          [(vec ids)]))
          missing (remove e->title ids)]
      (when (seq missing)
        (cli-util/error "No entity found with id:" (str/join ", " missing)))
      (into {} (map (fn [id] [(e->title id) id])) ids))))

(defn- args->name->id
  "Resolves the mix of page name and :db/id args to `{name id}`."
  [graph args]
  (let [ids (keep parse-long args)
        names (remove parse-long args)]
    (merge (names->ids graph names) (ids->names graph ids))))

(defn- opensearch-page-urls
  "Returns up to 5 Wikipedia page urls found by `page-name`'s opensearch
  results, used to disambiguate a \"may refer to\" search result."
  [page-name]
  (let [search-url (str "https://en.wikipedia.org/w/api.php?action=opensearch&search="
                        (URLEncoder/encode page-name "UTF-8") "&format=json")
        body (:body (http/get search-url {:timeout 5000}))
        urls (last (json/parse-string body))]
    (into [] (comp (filter #(str/starts-with? % "https://en.wikipedia.org/wiki/"))
                   (take 5))
          urls)))

(defn- wikipedia-url
  "Resolves `page-name` against Wikipedia search, following the redirect.
  Returns the resolved url, a vector of candidate urls if the result is a
  disambiguation page (\"may refer to\"), or nil if identification failed
  (still on the search page)."
  [page-name]
  (try
    (let [search-url (str "http://en.wikipedia.org/wiki/Special:Search?search="
                          (URLEncoder/encode page-name "UTF-8"))
          {:keys [uri body]} (http/get search-url {:timeout 5000})
          url (str uri)]
      (cond
        (str/includes? url "/wiki/Special:Search") nil
        (str/includes? body "may refer to") (not-empty (opensearch-page-urls page-name))
        :else url))
    (catch Exception _ nil)))

(defn- select-candidate-url
  "Prompts the user to choose one of `urls` for `name`, returning the chosen
  url, or nil if no valid choice was given."
  [name urls]
  (println (str "Multiple results found for " name ":"))
  (doseq [[i url] (map-indexed vector urls)]
    (printf "%d. %s%n" (inc i) url))
  (print "Select one: ")
  (flush)
  (when-let [i (input/parse-single-select (or (read-line) "") (count urls))]
    (nth urls (dec i))))

(defn- resolve-urls
  "Resolves each name's Wikipedia url, returning
  `{:successes [{:name :id :url}] :failures [name]}`."
  [name->id]
  (reduce (fn [acc [name id]]
            (let [result (wikipedia-url name)
                  url (if (vector? result) (select-candidate-url name result) result)]
              (if url
                (update acc :successes conj {:name name :id id :url url})
                (update acc :failures conj name))))
          {:successes [] :failures []}
          name->id))

(defn- save-urls! [graph selected]
  (doseq [{:keys [id url]} selected]
    (apply shell "logseq" "upsert" "page"
           (concat (graph-args graph) ["--id" (str id) "--update-properties" (pr-str {"url" url})]))))

(defn- select-and-save! [graph successes]
  (if (empty? successes)
    (println "No urls identified.")
    (do
      (doseq [[i {:keys [name url]}] (map-indexed vector successes)]
        (printf "%d. %s - %s%n" (inc i) name url))
      (print "\nSelect pages to save url for: ")
      (flush)
      (let [n (count successes)
            indices (->> (input/parse-multi-select (or (read-line) "") n) distinct sort)
            invalid (remove #(<= 1 % n) indices)]
        (cond
          (empty? indices) (println "Nothing selected.")
          (seq invalid) (cli-util/error "Invalid indices:" (str/join "," invalid))
          :else (save-urls! graph (map #(successes (dec %)) indices)))))))

(defn- print-failures [failures]
  (when (seq failures)
    (println "Failed to identify:" (str/join ", " failures))))

(defn- command [{:keys [opts]}]
  (let [{:keys [graph args]} opts
        {:keys [successes failures]} (resolve-urls (args->name->id graph args))]
    (select-and-save! graph successes)
    (print-failures failures)))

(def ^:private spec
  {:graph {:alias :g :desc "Graph name" :complete-fn logseq-util/complete-graphs}
   :args {:coerce [:string]
          :positional true
          :require true
          :desc "Page names or ids (:db/id) to identify"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec :args->opts (repeat :args) :restrict-args true}]
                args
                {:prog "logseq-identify-pages" :help true}))
