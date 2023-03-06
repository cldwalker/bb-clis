(ns cldwalker.bb-clis.tasks.logseq
  "Logseq related tasks"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [babashka.process :refer [process shell]]
            [babashka.fs :as fs]
            [bb-dialog.core :as bb-dialog]
            [clojure.pprint :as pprint]
            [cldwalker.bb-clis.cli.logseq :as logseq]))

;; Ast tasks
;; =========
(defn empty-files
  "Reads in stdin input from logseq-ast and prints files which have no content"
  ;; Empty is no nodes or one node with blank heading
  ;; Worked for all except one node with a property drawer that wasn't just timestamps
  []
  (let [ast-files (edn/read *in*)
        empty-files (filter
                     (fn [{:keys [ast]}]
                       (let [h1 (filter (fn [x] (= "Heading" (ffirst x))) ast)]
                         (or (zero? (count h1))
                             (and (= 1 (count h1)) (empty? (:title (second (ffirst h1))))))))
                     ast-files)]
    (prn (map :file empty-files))))

(defn- ast->pages [ast]
  (->> ast
       (mapcat (fn [node]
                 (when (= "Heading" (ffirst node))
                   (filter #(and (= "Link" (first %))
                                 (= "Page_ref" (-> % second :url first)))
                           (-> node first second :title)))))
       (map #(-> % second :url second))))

(defn pages
  "Reads in stdin input from logseq-ast and prints pages"
  []
  ;; ast-in can be a coll of asts by file or just the ast itself
  (let [ast-in (edn/read *in*)
        result-pages (if (and (coll? ast-in) (:file (first ast-in)))
                       (keep #(when-let [pages (seq (ast->pages (:ast %)))]
                                {:file (:file %) :pages pages})
                             ast-in)
                       (ast->pages ast-in))]
    (prn result-pages)))

(defn- ast->urls [ast]
  (->> ast
       (mapcat (fn [node]
                 (when (= "Heading" (ffirst node))
                   (filter #(and (= "Link" (first %))
                                 (not (contains? #{"Page_ref" "Block_ref"} (-> % second :url first))))
                           (-> node first second :title)))))
       (map (fn [x]
              ;; if a markdown label
              (if (str/starts-with? (-> x second :full_text) "[")
                (as-> (-> x second :url second) m
                      (str (:protocol m) "://" (:link m)))
                (-> x second :full_text))))))

(defn urls
  "Reads in stdin input from logseq-ast and prints urls"
  []
  ;; ast-in can be a coll of asts by file or just the ast itself
  (let [ast-in (edn/read *in*)
        result-urls (if (and (coll? ast-in) (:file (first ast-in)))
                      (keep #(when-let [urls (seq (ast->urls (:ast %)))]
                               {:file (:file %) :urls urls})
                            ast-in)
                      (ast->urls ast-in))]
    (prn result-urls)))

;; Data migration
;; =============
(defn- search-graphs
  [dir & search-terms]
  (-> (process (str "find " dir " -name .git -prune -o -name '*' -print"))
      (process {:out :string} "grep -i -E" (str/join "|" search-terms))
      deref
      :out
      str/split-lines))

(defn copy-entities
  "Copy mp entities to current graph"
  [& search-terms]
  (doseq [search-term search-terms]
    (if-let [ent (->> (shell {:out :string} "mp show -e" search-term "-r")
                      :out
                      edn/read-string
                      :full-entity)]
      (let [camel-case #(str/replace % #"(?:^|-)(\S)" (fn [x] (str/capitalize (second x))))
            [type & tags] (:tags ent)
            type' (let [tag (if (= "type" type) "class" type)]
                    (format "[[%s]]" (camel-case tag)))]
        (spit (str "pages/" (camel-case search-term) ".md")
              (logseq/properties->block (cond-> {}
                                                (seq type')
                                                (assoc :type type')
                                                (and (some? (:url ent))
                                                     (not (str/includes? (:url ent) "https://notes.pinboard.in")))
                                                (assoc :url (:url ent))
                                                (seq (:description ent))
                                                (assoc :desc (:description ent))
                                                (seq tags)
                                                (merge (into {}
                                                             (map #(str/split % #":") tags)))))))
      (println "Nothing found for" search-term))))

(defn copy-files
  "Copy files from one graph to another"
  [dir & search-terms]
  (let [files (apply search-graphs dir search-terms)]
    (if (= files [""])
      (println "Error: No files found")
      (if-let [chosen-files
               (seq (bb-dialog/checklist "Copy files"
                                         "Choose files to copy between graphs"
                                         (map #(vector % "" false) files) {:out-fn str}))]
        (do
          (println (concat ["cp"] chosen-files ["pages"]))
          (apply shell (concat ["cp"] chosen-files ["pages"])))
        (println "Error: No files copied since none chosen")))))

(defn- get-graph-files
  "If one dir given, assume all children are graph dirs else assume each dir is
  a graph"
  [dirs]
  (if (= 1 (count dirs))
    (fs/glob (first dirs) "*/pages/*")
    (mapcat #(fs/glob % "pages/*") dirs)))

;; TODO: Handle case-insensitive common pages like Clojure vs clojure
(defn validate-common-pages
  "Find common pages across graphs and validate that they are equal"
  [& dirs]
  (let [not-equal (->> (get-graph-files dirs)
                       (map str)
                       (group-by fs/file-name)
                       (filter #(> (count (val %)) 1))
                       (filter #(apply not= (map slurp (val %)))))]
    (if (empty? not-equal)
      (println "Success! All common pages are equal")
      (do
        (println "Doh! The following common pages are not equal:")
        (pprint/pprint not-equal)))))

(defn list-common-pages
  "List common pages"
  [& dirs]
  (->> (get-graph-files dirs)
       (map str)
       (group-by fs/file-name)
       (filter #(> (count (val %)) 1))
       (sort-by #(count (second %)) >)
       (map (fn [[k files]]
              (into {:file k}
                    (map #(let [graph (or (keyword (second (re-find #"([^/]+)/pages/" %)))
                                          (throw (ex-info "Can't extract graph name"
                                                          {:file %})))]
                            [graph "x"])
                         files))))
       pprint/print-table))
