(ns cldwalker.bb-clis.bin.logseq-my-schema
  "Manages a personal graph-ontology index for Logseq graphs.

  `build-index` reads the Property and Class pages of every markdown graph in
  a directory into sqlite.build EDN, merging the graphs' results. Pages unique
  to the graphs go to resources/tagaholic-me-index.edn, in the same shape as
  schema-org-index.edn. Pages whose `url::` matches a schema.org class or
  property go to resources/my-schema-org-index.edn as a minimal entry under
  the schema.org name, so `add` never duplicates a schema.org concept under a
  local name.

  `add` imports classes and properties from tagaholic-me-index.edn into a
  graph, like [[cldwalker.bb-clis.bin.logseq-schema]] `add` does for
  schema.org. `update` applies my-schema-org-index.edn's aliases and meta
  notes to the schema.org classes and properties already in a graph.
  `migrate` replaces user classes and properties named in
  tagaholic-me-index.edn with their tagaholic.me equivalents, like
  [[cldwalker.bb-clis.bin.logseq-schema]] `migrate` does for schema.org."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.logseq :as logseq-util]
            [cldwalker.bb-clis.util.logseq-migrate :as logseq-migrate]
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
(defn- schema-property-ident [k] (keyword "schema.property" (name k)))

(def ^:private my-class-ns "tagaholic.me.class")
(def ^:private my-property-ns "tagaholic.me.property")

(defn- my-class-ident [k] (keyword my-class-ns (name k)))
(defn- my-property-ident [k] (keyword my-property-ns (name k)))

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
      (get props "desc")
      (assoc :logseq.property/description (get props "desc"))
      (get props "meta")
      (assoc :meta (get props "meta")))))

(defn- declared-alias [props] (some-> (get props "alias") safe-keyword))

(defn- local-alias
  "Returns a local page's declared `alias::` as a `:build/alias` vector, or
  nil. The page's own name is left out since it's already the entry key"
  [props]
  (some-> (declared-alias props) vector))

(defn- migrated-alias
  "Returns a page's former local name plus any declared `alias::`, deduped.
  Used as `:build/alias` once the page merges into a schema.org entry"
  [page-name props]
  (vec (distinct (remove nil? [(safe-keyword page-name) (declared-alias props)]))))

(defn- property-entry [page-name props]
  (let [range-includes (or (get props "rangeIncludes")
                           (cli-util/error page-name "is missing a required rangeIncludes key"))]
    (cond-> {:block/title page-name
             :logseq.property/type (get range-includes->type range-includes :default)
             :build/properties (build-properties page-name props)}
      (local-alias props) (assoc :build/alias (local-alias props)))))

(defn- class-extends
  "Resolves `parent` to a `:build/class-extends` vector. A schema.org class
  resolves to its schema.class ident, a key in `aliases` to the class it
  aliases, and anything else to a local class key"
  [aliases parent]
  [(let [k (safe-keyword parent)]
     (cond
       (contains? schema-org-classes k) (schema-class-ident k)
       (contains? aliases k) (aliases k)
       :else k))])

(defn- class-entry [aliases page-name props]
  (cond-> {:block/title page-name
           :build/properties (build-properties page-name props)}
    (local-alias props) (assoc :build/alias (local-alias props))
    (get props "parent") (assoc :build/class-extends (class-extends aliases (get props "parent")))))

(defn- page-entries
  "Reads every page under `graph-dir`/pages into `{:name :type :props}` maps"
  [graph-dir]
  (for [path (fs/glob (fs/path graph-dir "pages") "*.md")
        :let [props (parse-properties (str/split-lines (slurp (fs/file path))))]]
    {:name (page-name path) :type (get props "type") :props props}))

(defn- class-aliases
  "Maps each local class's `alias::` keyword to the class's own key. Lets
  [[class-extends]] resolve a `parent::` that uses an alias"
  [class-entries]
  (into {}
        (mapcat (fn [{:keys [name props]}]
                  (map (fn [a] [a (safe-keyword name)]) (local-alias props))))
        class-entries))

(defn- schema-org-aliases
  "Maps each `:build/alias` of a schema.org-matched class to that class's
  schema.class ident. Lets [[class-extends]] resolve a `parent::` that uses
  a former local name or alias"
  [my-schema-classes]
  (into {}
        (mapcat (fn [[k v]] (map (fn [a] [a (schema-class-ident k)]) (:build/alias v))))
        my-schema-classes))

(defn- entries->map [f entries]
  (into {} (map (fn [{:keys [name props]}] [(safe-keyword name) (f name props)])) entries))

(defn- url->schema-org-key
  "Maps each `:url` in `schema-org-entries`, an index's `:classes` or
  `:properties`, to its entry key"
  [schema-org-entries]
  (into {} (keep (fn [[k v]] (when-let [url (get-in v [:build/properties :url])] [url k]))) schema-org-entries))

(def ^:private schema-org-class-url->key (url->schema-org-key schema-org-classes))
(def ^:private schema-org-property-url->key (url->schema-org-key schema-org-properties))

(defn- migrated-properties
  "Returns `:build/properties` holding the page's `meta::` note, or nil.
  Everything else a schema.org-matched page has is already in
  schema-org-index.edn"
  [props]
  (when (get props "meta")
    {:tagaholic.me/meta (get props "meta")}))

(defn- split-schema-org-matches
  "Splits `entries` into `[local matched]` by whether a page's `url::` is in
  `url->key`. Matched pages become entries keyed by schema.org name with only
  their `:build/alias` and `meta::`, and are dropped when they have neither"
  [url->key entries]
  (reduce (fn [[local matched] {:keys [name props] :as entry}]
            (if-let [k (get url->key (get props "url"))]
              (let [alias (vec (remove #{k} (migrated-alias name props)))
                    properties (migrated-properties props)
                    matched-entry (cond-> {}
                                    (seq alias) (assoc :build/alias alias)
                                    properties (assoc :build/properties properties))]
                [local (cond-> matched (seq matched-entry) (assoc k matched-entry))])
              [(conj local entry) matched]))
          [[] {}]
          entries))

(defn- build-index [graph-dir]
  (let [entries (page-entries graph-dir)
        by-type (fn [type] (filter #(= type (:type %)) entries))
        [local-classes my-schema-classes] (split-schema-org-matches schema-org-class-url->key (by-type "Class"))
        [local-props my-schema-props] (split-schema-org-matches schema-org-property-url->key (by-type "Property"))
        aliases (merge (class-aliases local-classes) (schema-org-aliases my-schema-classes))]
    {:tagaholic {:graph-namespace :tagaholic.me
                 :classes (entries->map (partial class-entry aliases) local-classes)
                 :properties (entries->map property-entry local-props)}
     :my-schema-org {:graph-namespace :schema
                     :classes my-schema-classes
                     :properties my-schema-props}}))

(def ^:private index-path "resources/tagaholic-me-index.edn")
(def ^:private my-schema-org-index-path "resources/my-schema-org-index.edn")

(def ^:private index-overrides
  "Entries merged over the merged graphs' indexes, keyed like a [[build-index]]
  result. An override's keys replace an entry's, so [[index-conflicts]] skips
  them and an override can add an entry missing from some or all graphs"
  {:tagaholic
   {:classes {:LargeLanguageModel
              {:build/alias [:LLM]}}
    :properties {:referrerURL
                 {:block/title "referrerURL",
                  ;; Different than MD
                  :logseq.property/type :url,
                  :build/alias [:via]
                  :build/properties
                  {:url "https://tagaholic.me/schema/referrerURL.html",
                   :logseq.property/description
                   "Url or description for where this node came from"}}}}
   :my-schema-org
   {:classes {:Corporation
              {:build/alias [:Company]}
              :IndividualProduct
              {:build/alias [:IProduct]}
              ;; Different than MD
              :SoftwareSourceCode
              {:build/alias [:Repository]}
              :Question
              ;; Different than MD
              {:build/alias [:Q]
               :build/class-properties [:tagaholic.me.property/answer]}}
    :properties {:about
                 {:logseq.property/ui-position :block-right}
                 :attendee
                 {:logseq.property/ui-position :block-right}}}})

(defn- graph-dirs
  "Returns the markdown graph directories in `dir`: `dir` itself when it has a
  pages directory, else its children that do"
  [dir]
  (if (fs/directory? (fs/path dir "pages"))
    [dir]
    (sort (map fs/parent (filter fs/directory? (fs/glob dir "*/pages"))))))

(defn- index-conflicts
  "Returns a conflict for each `:classes` or `:properties` key whose value
  differs across `graph->index`, a seq of `[graph index]` pairs. Keys set by
  the entry's `overrides` aren't compared"
  [index-name overrides graph->index]
  (for [section [:classes :properties]
        [k graph-values] (group-by first (for [[graph index] graph->index
                                               [k v] (get index section)]
                                           [k graph (apply dissoc v (keys (get-in overrides [section k])))]))
        :when (< 1 (count (set (map last graph-values))))]
    {:index index-name :section section :key k
     :values (into (sorted-map) (map (fn [[_ graph v]] [graph v])) graph-values)}))

(defn- merge-index [overrides indexes]
  (let [merged (reduce (fn [acc index]
                         (-> acc
                             (update :classes merge (:classes index))
                             (update :properties merge (:properties index))))
                       indexes)]
    (-> merged
        (update :classes #(merge-with merge % (:classes overrides)))
        (update :properties #(merge-with merge % (:properties overrides))))))

(defn- merge-graph-indexes
  "Merges each graph's [[build-index]] result in `graph->index`, a seq of
  `[graph result]` pairs, then applies `overrides`, shaped like a result.
  Errors listing every class and property whose value differs across graphs,
  ignoring overridden keys"
  [overrides graph->index]
  (let [index-of (fn [k] (map (fn [[graph result]] [graph (k result)]) graph->index))
        conflicts (concat (index-conflicts index-path (:tagaholic overrides) (index-of :tagaholic))
                          (index-conflicts my-schema-org-index-path (:my-schema-org overrides) (index-of :my-schema-org)))]
    (when (seq conflicts)
      (cli-util/error
       (str/join "\n"
                 (cons "These classes and properties differ across graphs:"
                       (for [{:keys [index section key values]} conflicts]
                         (str/join "\n" (cons (str index " " section " " key)
                                              (map (fn [[graph v]]
                                                     (str "  " graph ": " (binding [*print-namespace-maps* false] (pr-str v))))
                                                   values))))))))
    {:tagaholic (merge-index (:tagaholic overrides) (map second (index-of :tagaholic)))
     :my-schema-org (merge-index (:my-schema-org overrides) (map second (index-of :my-schema-org)))}))

(defn- build-index-command [{:keys [opts]}]
  (let [dir (fs/expand-home (:directory opts))
        dirs (when (fs/directory? dir) (graph-dirs dir))]
    (when (empty? dirs)
      (cli-util/error "No graph directories found in" (str dir)))
    (println "Reading" (count dirs) "graphs:" (str/join ", " (map fs/file-name dirs)))
    (let [{:keys [tagaholic my-schema-org]}
          (merge-graph-indexes index-overrides (map (fn [d] [(str (fs/file-name d)) (build-index d)]) dirs))]
      (spit index-path (with-out-str (pprint/pprint tagaholic)))
      (spit my-schema-org-index-path (with-out-str (pprint/pprint my-schema-org)))
      (println "Wrote" (count (:classes tagaholic)) "classes and"
               (count (:properties tagaholic)) "properties to" index-path)
      (println "Wrote" (count (:classes my-schema-org)) "classes and"
               (count (:properties my-schema-org)) "properties to" my-schema-org-index-path))))

;; ---- add ----

(def ^:private my-index
  (some-> (io/resource "tagaholic-me-index.edn") slurp edn/read-string))

(defn- read-my-index []
  (or my-index (cli-util/error "Unable to find tagaholic-me-index.edn on classpath")))

(def ^:private my-name-candidates
  "Completion candidates for `add` args, in the same `{:value :description}`
  shape as [[cldwalker.bb-clis.bin.logseq-schema]] uses"
  (mapv (fn [[k v]]
          (let [desc (get-in v [:build/properties :logseq.property/description])]
            (cond-> {:value (symbol (name k))}
              desc (assoc :description (str/trim (str/replace desc #"\s+" " "))))))
        (concat (:classes my-index) (:properties my-index))))

(defn- normalize-cardinality [m]
  (assoc m :db/cardinality
         (if (= :many (:db/cardinality m)) :db.cardinality/many :db.cardinality/one)))

(defn- qualify-my-properties
  "Qualifies bare `:build/properties` keys in `m`. `:url` becomes schema.org's
  url property since this index has none, and any other key becomes a
  tagaholic.me property"
  [m]
  (cond-> m
    (:build/properties m)
    (update :build/properties update-keys
            #(cond (qualified-keyword? %) %
                   (= :url %) (schema-property-ident :url)
                   :else (my-property-ident %)))))

(defn- full-class-entry
  "Builds the import entry for class `k`, qualifying local
  `:build/class-extends` parents and dropping index-only `:build/alias`.
  Qualified parents, e.g. schema.org classes, pass through as is"
  [index k]
  (let [v (dissoc (get-in index [:classes k]) :build/alias)]
    (cond-> (qualify-my-properties v)
      (:build/class-extends v)
      (update :build/class-extends
              #(set (map (fn [p] (if (qualified-keyword? p) p (my-class-ident p))) %))))))

(defn- full-property-entry [index k]
  (-> (get-in index [:properties k])
      (dissoc :build/alias)
      qualify-my-properties
      (assoc :block/title (name k))
      normalize-cardinality))

(defn- anchor-entry
  "Builds a minimal declaration of existing property `entry`, titled `title`.
  The import's undeclared-property check requires one for every property the
  imported `:build/properties` use, even ones already in the graph"
  [entry title]
  (-> entry
      (select-keys [:logseq.property/type :db/cardinality])
      (assoc :block/title title)
      normalize-cardinality))

(defn- uses-meta?
  "Returns true if any of `new-classes` or `new-props` sets the meta property"
  [index new-classes new-props]
  (boolean (some #(contains? (get-in index (conj % :build/properties)) :meta)
                 (concat (map (partial vector :classes) new-classes)
                         (map (partial vector :properties) new-props)))))

(defn- closure
  "Returns the set of classes needed to add `class-args`: each class plus its
  local `:build/class-extends` ancestors. Qualified parents are left out, and
  a bare parent missing from `index` errors"
  [index class-args]
  (loop [need-classes #{}
         class-queue (vec class-args)]
    (if (empty? class-queue)
      need-classes
      (let [c (first class-queue)
            rest-queue (subvec class-queue 1)]
        (cond
          (need-classes c) (recur need-classes rest-queue)
          (not (contains? (:classes index) c)) (cli-util/error "Class" c "not found in index")
          :else (recur (conj need-classes c)
                       (into rest-queue
                             (remove qualified-keyword?
                                     (get-in index [:classes c :build/class-extends])))))))))

(defn- error-if-duplicates
  "Errors if any of `check-classes` or `check-props` matches the title of an
  existing user class or property, since importing it would create an
  unusable duplicate"
  [graph check-classes check-props]
  (let [dup-class-title? (logseq-util/existing-user-class-titles graph (map name check-classes))
        dup-prop-title? (logseq-util/existing-property-titles graph (map name check-props))
        dups (concat (filter (comp dup-class-title? name) check-classes)
                     (filter (comp dup-prop-title? name) check-props))]
    (when (seq dups)
      (cli-util/error
       (str "Cannot add these classes or properties which already exist: "
            (str/join ", " (map #(str "'" (name %) "'") dups)))))))

(defn- schema-org-parents
  "Returns the schema.org `:build/class-extends` parents of `class-ks` in `index`"
  [index class-ks]
  (into #{}
        (comp (mapcat #(get-in index [:classes % :build/class-extends]))
              (filter #(= "schema.class" (namespace %))))
        class-ks))

(defn- error-if-missing-schema-org-parents
  "Errors if any schema.org `parents` aren't in `existing`, since the Logseq CLI
  reports success on an import that extends a missing class but imports nothing"
  [existing parents]
  (when-let [missing (seq (sort (remove existing parents)))]
    (cli-util/error "Cannot add classes whose schema.org parents aren't in the graph:"
                    (pr-str (vec missing))
                    (str "\nRun 'logseq-schema add " (str/join " " (map name missing)) "' first"))))

(defn- alias-pages
  "Returns `:pages` creating each of `alias-names` plus the `:alias` refs to
  them. The import reuses an existing page with the same title instead"
  [alias-names]
  (let [uuids (vec (repeatedly (count alias-names) random-uuid))]
    {:pages (mapv (fn [uuid title] {:page {:block/title title :block/uuid uuid :build/keep-uuid? true}})
                  uuids alias-names)
     :alias (set (map #(vector :block/uuid %) uuids))}))

(defn- aliased-entities
  "Builds `{ident entry}` for keys `ks` of `index-entries`, adding `:block/alias`
  to entries with a `:build/alias`. Returns them as `:entities` with the alias
  `:pages` to create"
  [index-entries ks ident-fn entry-fn]
  (reduce (fn [acc k]
            (let [{:keys [pages alias]} (some->> (seq (get-in index-entries [k :build/alias]))
                                                 (map name)
                                                 alias-pages)]
              (-> acc
                  (assoc-in [:entities (ident-fn k)] (cond-> (entry-fn k) alias (assoc :block/alias alias)))
                  (update :pages into pages))))
          {:entities {} :pages []}
          ks))

(defn- add-edn
  "Assembles the import EDN for `new-classes` and `new-props` plus the alias
  pages they need. Declares schema.org's url, and the existing meta property
  when `meta-anchor?`, since the import requires every used property declared"
  [index new-classes new-props meta-anchor?]
  (let [{classes-map :entities class-pages :pages}
        (aliased-entities (:classes index) new-classes my-class-ident #(full-class-entry index %))
        {props-map :entities prop-pages :pages}
        (aliased-entities (:properties index) new-props my-property-ident #(full-property-entry index %))
        props-map (cond-> props-map
                    (or (seq classes-map) (seq props-map))
                    (assoc (schema-property-ident :url) (anchor-entry (:url schema-org-properties) "url"))
                    meta-anchor?
                    (assoc (my-property-ident :meta) (anchor-entry (get-in index [:properties :meta]) "meta")))
        pages (into class-pages prop-pages)]
    (cond-> {}
      (seq classes-map) (assoc :classes classes-map)
      (seq props-map) (assoc :properties props-map)
      (seq pages) (assoc :pages-and-blocks pages))))

(defn- build-add-edn
  "Builds import EDN for `arg-kws` plus their local ancestor classes not yet
  in `graph`, erroring if an arg already exists or a new class's schema.org
  parent doesn't. The meta property and alias pages are added too when an
  entry uses them. Returns a map of `:new-classes`, `:new-props` and `:edn`"
  [index graph arg-kws]
  (let [{:keys [classes properties]} index
        class-args (filter #(contains? classes %) arg-kws)
        prop-args (filter #(contains? properties %) arg-kws)
        need-classes (closure index class-args)
        schema-url (schema-property-ident :url)
        meta-ident (my-property-ident :meta)
        existing (logseq-util/existing-idents graph
                                              (into #{schema-url meta-ident}
                                                    (concat (schema-org-parents index need-classes)
                                                            (map my-class-ident need-classes)
                                                            (map my-property-ident prop-args))))
        new-classes (remove (comp existing my-class-ident) need-classes)
        arg-new-props (remove (comp existing my-property-ident) prop-args)
        meta? (and (not-any? #{:meta} arg-new-props) (uses-meta? index new-classes arg-new-props))
        new-props (cond-> arg-new-props (and meta? (not (existing meta-ident))) (concat [:meta]))
        _ (error-if-duplicates graph
                               (distinct (concat class-args new-classes))
                               (distinct (concat prop-args new-props)))
        _ (when-not (existing schema-url)
            (cli-util/error ":schema.property/url not found in graph. Run 'logseq-schema init' first"))
        _ (error-if-missing-schema-org-parents existing (schema-org-parents index new-classes))]
    {:new-classes new-classes
     :new-props new-props
     :edn (add-edn index new-classes new-props (and meta? (existing meta-ident)))}))

(defn- add-schema
  "Adds class and property `args` to `:graph`, or prints their EDN when
  `:pretend` is set"
  [args {:keys [graph pretend]}]
  (let [index (read-my-index)
        arg-kws (distinct (map keyword args))
        unknown (remove #(or (contains? (:classes index) %)
                             (contains? (:properties index) %))
                        arg-kws)
        _ (when (seq unknown)
            (cli-util/error "These are not classes or properties in tagaholic-me-index.edn:"
                            (pr-str (vec unknown))))
        {:keys [new-classes new-props edn]} (build-add-edn index graph arg-kws)]
    (println "Adding" (count new-classes) (logseq-migrate/pluralize new-classes "class" "classes") "and"
             (count new-props) (logseq-migrate/pluralize new-props "property" "properties") "-"
             (into (vec new-classes) new-props))
    (if pretend
      (pprint/pprint edn)
      (logseq-util/import-edn graph edn))))

(defn- add-command [{{:keys [args] :as opts} :opts}]
  (when (empty? args)
    (cli-util/error "Usage: logseq-my-schema add [& PROPERTY-OR-CLASSES]"))
  (add-schema args opts))

(def ^:private add-spec
  {:graph {:alias :g
           :desc "Graph name"
           :complete-fn logseq-util/complete-graphs}
   :pretend {:alias :n :coerce :boolean
             :desc "Print the resulting EDN instead of importing it"}
   :args {:coerce [:symbol]
          :complete my-name-candidates
          :desc "tagaholic-me-index.edn classes or properties to add"}})

;; ---- update ----

(def ^:private my-schema-org-index
  (some-> (io/resource "my-schema-org-index.edn") slurp edn/read-string))

(defn- read-my-schema-org-index []
  (or my-schema-org-index (cli-util/error "Unable to find my-schema-org-index.edn on classpath")))

(def ^:private update-type->index-key {"class" :classes "property" :properties})

(defn- schema-org-ident
  "Returns the graph ident of my-schema-org-index.edn key `k` under `index-key`.
  A qualified `k`, e.g. `:logseq.property/description`, is already an ident"
  [index-key k]
  (cond (qualified-keyword? k) k
        (= :classes index-key) (schema-class-ident k)
        :else (schema-property-ident k)))

(defn- index-meta [entry] (get-in entry [:build/properties :tagaholic.me/meta]))

(def ^:private update-name-candidates
  "Completion candidates for `update` args, described by their aliases or meta"
  (mapv (fn [[k v]]
          (let [desc (or (some->> (seq (:build/alias v)) (map name) (str/join ", ") (str "alias: "))
                         (index-meta v))]
            (cond-> {:value (symbol (name k))}
              desc (assoc :description desc))))
        (concat (:classes my-schema-org-index) (:properties my-schema-org-index))))

(defn- update-graph-state
  "Fetches the uuids, alias titles and meta values of `idents` in `graph`.
  `:uuids` also has the meta property's uuid when it exists"
  [graph idents]
  (let [inputs [(vec idents)]]
    {:uuids (logseq-util/idents->uuids graph (conj (set idents) (my-property-ident :meta)))
     :aliases (reduce (fn [m [ident title]] (update m ident (fnil conj #{}) title))
                      {}
                      (logseq-util/logseq-query graph
                                                '[:find ?ident ?title :in $ [?ident ...]
                                                  :where [?e :db/ident ?ident] [?e :block/alias ?a] [?a :block/title ?title]]
                                                inputs))
     :metas (into {} (logseq-util/logseq-query graph
                                               '[:find ?ident ?meta :in $ [?ident ...]
                                                 :where [?e :db/ident ?ident]
                                                 [?e :tagaholic.me.property/meta ?m] [?m :block/title ?meta]]
                                               inputs))}))

(defn- entity-update
  "Returns the `:pages` updating `ident` with the `entry` aliases and meta that
  differ from `state`, or nil when none do. Aliases are only added since an
  import can't remove them"
  [state ident entry]
  (let [current (set (map str/lower-case (get-in state [:aliases ident])))
        aliases (remove #(current (str/lower-case %)) (map name (:build/alias entry)))
        meta (index-meta entry)
        meta? (and (some? meta) (not= meta (get-in state [:metas ident])))
        {:keys [pages alias]} (alias-pages aliases)]
    (when (or (seq aliases) meta?)
      {:meta? meta?
       :pages (conj pages
                    ;; empty :build/properties forces the full page-update path so :block/alias sticks
                    {:page (cond-> {:block/uuid (get-in state [:uuids ident])
                                    :build/properties (cond-> {} meta? (assoc (my-property-ident :meta) meta))}
                             (seq aliases) (assoc :block/alias alias))})})))

(defn- meta-declarations
  "Returns `:properties` declaring the meta property, which the import requires
  whenever it's used. A meta property not in the graph gets its full entry"
  [my-index meta-exists?]
  (if meta-exists?
    {(my-property-ident :meta) (anchor-entry (get-in my-index [:properties :meta]) "meta")}
    {(my-property-ident :meta) (full-property-entry my-index :meta)
     (schema-property-ident :url) (anchor-entry (:url schema-org-properties) "url")}))

(defn- build-update-edn
  "Builds import EDN updating `entries`, a my-schema-org-index.edn `index-key`
  map, whose aliases or meta differ from `state` (see [[update-graph-state]]).
  Returns entry keys grouped as `:updated`, `:current` and `:missing`, plus
  `:new-props` and `:edn`"
  [my-index index-key entries state]
  (let [results (for [[k entry] (sort-by key entries)
                      :let [ident (schema-org-ident index-key k)
                            exists? (contains? (:uuids state) ident)
                            change (when exists? (entity-update state ident entry))]]
                  {:k k :change change
                   :status (cond (not exists?) :missing change :updated :else :current)})
        status-ks (fn [status] (mapv :k (filter #(= status (:status %)) results)))
        changes (keep :change results)
        meta? (boolean (some :meta? changes))
        meta-exists? (contains? (:uuids state) (my-property-ident :meta))]
    {:updated (status-ks :updated)
     :current (status-ks :current)
     :missing (status-ks :missing)
     :new-props (if (and meta? (not meta-exists?)) [:meta] [])
     :edn (cond-> {}
            (seq changes) (assoc :pages-and-blocks (vec (mapcat :pages changes)))
            meta? (assoc :properties (meta-declarations my-index meta-exists?)))}))

(defn- select-update-entries
  "Returns the `index-key` entries of my-schema-org-index.edn named by `args`,
  or all of them when there are no `args`"
  [index-key args]
  (let [entries (get (read-my-schema-org-index) index-key)
        name->k (into {} (map (juxt name identity)) (keys entries))
        unknown (remove name->k (map str args))]
    (when (seq unknown)
      (cli-util/error (str "These are not " (name index-key) " in my-schema-org-index.edn:")
                      (pr-str (vec unknown))))
    (if (seq args) (select-keys entries (map (comp name->k str) args)) entries)))

(defn- update-schema
  "Updates the `:update-type` entities named by `args`, or all of them, in
  `:graph`, or prints their EDN when `:pretend` is set. Ends with a summary
  of how many were updated, already current or missing from the graph"
  [args {:keys [graph pretend update-type]}]
  (let [index-key (update-type->index-key update-type)
        entries (select-update-entries index-key args)
        state (update-graph-state graph (map #(schema-org-ident index-key %) (keys entries)))
        {:keys [updated current missing new-props edn]} (build-update-edn (read-my-index) index-key entries state)]
    (error-if-duplicates graph [] new-props)
    (cond
      pretend (pprint/pprint edn)
      (seq updated) (logseq-util/import-edn graph edn))
    (println (count updated) "to update," (count current) "exist and" (count missing) "don't exist")))

(defn- update-command [{{:keys [args] :as opts} :opts}]
  (update-schema args opts))

(def ^:private update-spec
  {:graph (:graph add-spec)
   :pretend {:alias :n :coerce :boolean
             :desc "Print the EDN to import and a summary instead of importing it"}
   :update-type {:alias :u :enum ["class" "property"] :default "class"
                 :desc "Type of schema.org entities to update"}
   :args {:coerce [:symbol]
          :complete update-name-candidates
          :desc "my-schema-org-index.edn classes or properties to update"}})

;; ---- migrate ----

(defn- migrate [opts]
  (logseq-migrate/migrate opts {:index (read-my-index)
                                :class-ident my-class-ident
                                :property-ident my-property-ident
                                :add-schema add-schema
                                :rename-prefix "logseq-my-schema."}))

(defn- migrate-command [{:keys [opts]}]
  (migrate opts))

(def ^:private migrate-spec
  (merge {:graph (:graph add-spec)
          :pretend {:alias :n :coerce :boolean
                    :desc "Print what would migrate instead of migrating"}}
         logseq-migrate/options))

(def ^:private table
  [{:cmds ["build-index"]
    :fn build-index-command
    :spec {:directory {:alias :d
                       :default "~/code/priv"
                       :desc "Directory of Logseq markdown graphs, or a single graph, to read Property/Class pages from"}}
    :doc (str "Build " index-path " and " my-schema-org-index-path
              " from the Property and Class pages of each graph in --directory.")}
   {:cmds ["add"]
    :fn add-command
    :spec add-spec
    :args->opts (repeat :args)
    :doc "Import classes or properties from tagaholic-me-index.edn that aren't
  in the graph yet. A class's local ancestors are included too; schema.org
  ancestors must already be in the graph (see logseq-schema add)."}
   {:cmds ["update"]
    :fn update-command
    :spec update-spec
    :args->opts (repeat :args)
    :doc "Update the schema.org classes or properties in the graph whose aliases
  or meta differ from my-schema-org-index.edn. Updates all of --update-type's
  entities unless given names. Aliases are only added, never removed."}
   {:cmds ["migrate"]
    :fn migrate-command
    :spec migrate-spec
    :doc "Replace user classes and properties whose names are in
  tagaholic-me-index.edn with their tagaholic.me equivalents, moving tags,
  property values and aliases from the originals onto the replacements. The
  originals are kept, renamed, unless --delete-existing is set. Use --pretend
  to preview what would change."}])

(defn -main [& args]
  (cli/dispatch table args {:prog "logseq-my-schema" :help true}))
