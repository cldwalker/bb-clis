(ns cldwalker.bb-clis.bin.logseq-my-schema
  "Manage a personal graph-ontology index for a Logseq graph. `build-index`
  reads a graph's Property and Class pages and writes them as sqlite.build
  EDN to resources/tagaholic-me-index.edn, in the same shape as
  schema-org-index.edn."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def ^:private schema-org-index
  (some-> (io/resource "schema-org-index.edn") slurp edn/read-string))

(def ^:private schema-org-classes (:classes schema-org-index))
(def ^:private schema-org-properties (:properties schema-org-index))

(def ^:private range-includes->type
  {"Date" :date
   "Float" :Number
   "Uri" :url
   "Boolean" :checkbox})

(defn- schema-class-ident [k] (keyword "schema.class" (name k)))

(defn- safe-keyword
  "Keywordizes a page name, replacing whitespace with '' since a keyword
  containing whitespace can't round-trip through pr-str/read-string"
  [page-name]
  (keyword (str/replace page-name #"\s+" "")))

(defn- strip-brackets
  "Strips a [[wikilink]] VALUE down to its inner text, leaving any other
  VALUE as is"
  [s]
  (if-let [[_ inner] (re-matches #"\[\[(.*)\]\]" s)]
    inner
    s))

(defn- parse-properties
  "Parses a page's markdown lines into a {key value} map of its `KEY:: VALUE`
  lines, ignoring lines that aren't in that form"
  [lines]
  (into {}
        (keep (fn [line]
                (when-let [[_ k v] (re-matches #"([A-Za-z0-9_-]+)::\s*(.*)" line)]
                  [k (strip-brackets (str/trim v))])))
        lines))

(defn- page-name [path] (fs/strip-ext (fs/file-name path)))

(defn- schema-url
  "A VALUE wrapped in {{}} is a template, resolved here to this graph's own
  schema url for the page; otherwise the VALUE is already a url"
  [page-name value]
  (if (str/starts-with? value "{{")
    (str "https://tagaholic.me/schema/" page-name ".html")
    value))

(defn- build-properties [page-name props]
  (let [url (or (get props "url")
                (cli-util/error page-name "is missing a required url key"))]
    (cond-> {:url (schema-url page-name url)}
      (get props "desc") (assoc :logseq.property/description (get props "desc")))))

(defn- property-entry [page-name props]
  (let [range-includes (or (get props "rangeIncludes")
                            (cli-util/error page-name "is missing a required rangeIncludes key"))]
    {:block/title page-name
     :logseq.property/type (get range-includes->type range-includes :default)
     :build/properties (build-properties page-name props)}))

(defn- class-extends
  "A parent that is also a schema.org class points at its schema.class ident;
  otherwise it's a class local to this graph"
  [parent]
  [(let [k (safe-keyword parent)]
     (if (contains? schema-org-classes k) (schema-class-ident k) k))])

(defn- class-entry [page-name props]
  (cond-> {:block/title page-name
           :build/properties (build-properties page-name props)}
    (get props "parent") (assoc :build/class-extends (class-extends (get props "parent")))))

(defn- page-entries
  "Reads every page under `graph-dir`/pages into `{:name :type :props}` maps"
  [graph-dir]
  (for [path (fs/glob (fs/path graph-dir "pages") "*.md")
        :let [props (parse-properties (str/split-lines (slurp (fs/file path))))]]
    {:name (page-name path) :type (get props "type") :props props}))

(defn- entries->map [f entries]
  (into {} (map (fn [{:keys [name props]}] [(safe-keyword name) (f name props)])) entries))

(defn- not-in? [schema-index] (fn [{:keys [name]}] (not (contains? schema-index (safe-keyword name)))))

(defn- build-index [graph-dir]
  (let [entries (page-entries graph-dir)
        by-type (fn [type] (filter #(= type (:type %)) entries))]
    {:graph-namespace :tagaholic.me
     :classes (entries->map class-entry (filter (not-in? schema-org-classes) (by-type "Class")))
     :properties (entries->map property-entry (filter (not-in? schema-org-properties) (by-type "Property")))}))

(def ^:private index-path "resources/tagaholic-me-index.edn")

(defn- build-index-command [{:keys [opts]}]
  (let [dir (fs/expand-home (:graph-directory opts))]
    (when-not (fs/directory? dir)
      (cli-util/error "Graph directory not found:" (str dir)))
    (let [index (build-index dir)]
      (spit index-path (with-out-str (pprint/pprint index)))
      (println "Wrote" (count (:classes index)) "classes and"
               (count (:properties index)) "properties to" index-path))))

(def ^:private table
  [{:cmds ["build-index"]
    :fn build-index-command
    :spec {:graph-directory {:alias :g
                             :default "~/code/priv/public-notes"
                             :desc "Logseq graph directory to read Property/Class pages from"}}
    :doc (str "Build " index-path " from the graph's Property and Class pages.")}])

(defn -main [& args]
  (cli/dispatch table args {:prog "logseq-my-schema" :help true}))
