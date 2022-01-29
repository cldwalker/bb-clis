(ns cldwalker.bb-clis.tasks.logseq
  "Logseq related tasks"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

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
