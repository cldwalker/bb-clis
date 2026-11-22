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
  schema.org. `update` applies tagaholic-me-index.edn's aliases and
  properties, e.g. url, sameAs and meta, to the tagaholic.me classes and
  properties already in a graph, and `schema-update` applies
  my-schema-org-index.edn's aliases and meta notes to the schema.org ones.
  `import` imports pages from markdown graphs with their tags and properties,
  adding any tags and properties the graph doesn't have yet. `migrate`
  replaces user classes and properties named in tagaholic-me-index.edn with
  their tagaholic.me equivalents, like [[cldwalker.bb-clis.bin.logseq-schema]]
  `migrate` does for schema.org."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [cldwalker.bb-clis.bin.logseq-schema :as logseq-schema]
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
  {"Boolean" :checkbox
   "Date" :date
   "DateRef" :date
   "Float" :number
   "Integer" :number
   "Uri" :url
   "Class" :class})

(def ^:private property->node-classes
  "Property pages whose rangeIncludes is a class, mapped to the
  `:build/property-classes` of their `:node` type"
  {"owner" [:schema.class/Person]
   "forCmd" [:tagaholic.me.class/Cmd]
   "forApp" [:schema.class/SoftwareApplication]})

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
  "Strips a lone [[wikilink]] VALUE down to its inner text, leaving any other
  VALUE, e.g. several wikilinks, as is"
  [s]
  (if-let [[_ inner] (re-matches #"\[\[([^\[\]]*)\]\]" s)]
    inner
    s))

(defn- page-refs
  "Returns the page names in a property value: each of its [[wikilinks]], or
  the whole value when it has none"
  [s]
  (or (seq (map second (re-seq #"\[\[([^\[\]]+)\]\]" s))) [s]))

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
  "Returns this graph's own schema url for the page"
  [page-name]
  (str "https://tagaholic.me/schema/" page-name ".html"))

(defn- build-properties
  "Builds a page's `:build/properties`. Its url is always this graph's own
  schema url. A `url::` wrapped in {{}} is a template for that url, and any
  other `url::` is an external url for the same concept, kept as sameAs"
  [page-name props]
  (let [url (or (get props "url")
                (cli-util/error page-name "is missing a required url key"))]
    (cond-> {:url (schema-url page-name)}
      (not (str/starts-with? url "{{"))
      (assoc (schema-property-ident :sameAs) url)
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

(defn- class-ref
  "Resolves class page name `class-name` to a class key. A schema.org class
  resolves to its schema.class ident, a key in `aliases` to the class it
  aliases, and anything else to a local class key"
  [aliases class-name]
  (let [k (safe-keyword class-name)]
    (cond
      (contains? schema-org-classes k) (schema-class-ident k)
      (contains? aliases k) (aliases k)
      :else k)))

(defn- domain-includes
  "Resolves a `domainIncludes::` value to a set of class idents, qualifying
  local classes since `:build/properties` values aren't qualified on import"
  [aliases v]
  (into #{}
        (map #(let [k (class-ref aliases %)] (if (qualified-keyword? k) k (my-class-ident k))))
        (page-refs v)))

(defn- property-entry [aliases page-name props]
  (let [range-includes (or (get props "rangeIncludes")
                           (cli-util/error page-name "is missing a required rangeIncludes key"))
        node-classes (get property->node-classes page-name)]
    (cond-> {:block/title page-name
             :logseq.property/type (if node-classes
                                     :node
                                     (or (get range-includes->type range-includes) :default))
             :build/properties (build-properties page-name props)}
      (get props "domainIncludes")
      (assoc-in [:build/properties (schema-property-ident :domainIncludes)]
                (domain-includes aliases (get props "domainIncludes")))
      node-classes (assoc :build/property-classes node-classes)
      (local-alias props) (assoc :build/alias (local-alias props)))))

(defn- class-entry [aliases page-name props]
  (cond-> {:block/title page-name
           :build/properties (build-properties page-name props)}
    (local-alias props) (assoc :build/alias (local-alias props))
    (get props "parent") (assoc :build/class-extends [(class-ref aliases (get props "parent"))])))

(defn- block-line? [line] (boolean (re-find #"^\s*-\s+\S" line)))

(defn- read-page
  "Reads the page at `path` into a `{:name :type :props :blocks}` map, where
  `:blocks` counts its non-empty blocks"
  [path]
  (let [lines (str/split-lines (slurp (fs/file path)))
        props (parse-properties lines)]
    {:name (page-name path) :type (get props "type") :props props
     :blocks (count (filter block-line? lines))}))

(defn- page-entries
  "Reads every page under `graph-dir`/pages (see [[read-page]])"
  [graph-dir]
  (map read-page (fs/glob (fs/path graph-dir "pages") "*.md")))

(defn- class-aliases
  "Maps each local class's `alias::` keyword to the class's own key. Lets
  [[class-ref]] resolve a class name that uses an alias"
  [class-entries]
  (into {}
        (mapcat (fn [{:keys [name props]}]
                  (map (fn [a] [a (safe-keyword name)]) (local-alias props))))
        class-entries))

(defn- schema-org-aliases
  "Maps each `:build/alias` of a schema.org-matched class to that class's
  schema.class ident. Lets [[class-ref]] resolve a class name that uses a
  former local name or alias"
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
                 :properties (entries->map (partial property-entry aliases) local-props)}
     :my-schema-org {:graph-namespace :schema
                     :classes my-schema-classes
                     :properties my-schema-props}}))

(def ^:private index-path "resources/tagaholic-me-index.edn")
(def ^:private my-schema-org-index-path "resources/my-schema-org-index.edn")

(def ^:private index-overrides
  "Entries merged over the merged graphs' indexes, keyed like a [[build-index]]
  result. An override's keys replace an entry's, so [[index-conflicts]] skips
  them and an override can add an entry missing from some or all graphs. A
  `:classes` or `:properties` map's `::dissoc` vector lists entries to remove"
  {:tagaholic
   {:classes {::dissoc [:Class :Property]
              :LargeLanguageModel
              {:build/alias [:LLM]}}
    :properties {::dissoc [:via :type :sameAs :domainIncludes :rangeIncludes :parent]
                 :referrerURL
                 {:block/title "referrerURL",
                  ;; Different than MD
                  :logseq.property/type :url,
                  :build/alias [:via]
                  :build/properties
                  {:url "https://tagaholic.me/schema/referrerURL.html",
                   :logseq.property/description
                   "Url or description for where this node came from"}}}}
   :my-schema-org
   {:classes {:Corporation {:build/alias [:Company]}
              :IndividualProduct {:build/alias [:IProduct]}
              ;; Different than MD
              :SoftwareSourceCode {:build/alias [:Repository]}
              ;; Different than MD
              :SocialMediaPosting {:build/alias []}
              :Question
              ;; Different than MD
              {:build/alias [:Q]
               :build/class-properties [:tagaholic.me.property/answer]}}
    :properties {;; Different than MD
                 :logseq.property/description {:build/alias []}
                 :about
                 {:logseq.property/ui-position :block-right}
                 :attendee
                 {:logseq.property/ui-position :block-right}
                 :domainIncludes
                 {:build/properties {:tagaholic.me.property/meta "A more useful version of rdfs:domain as it can be one of multiple values"}}
                 :rangeIncludes
                 {:build/properties {:tagaholic.me.property/meta "A more useful version of rdfs:range as it can be one of multiple values"}}}}})

(defn- graph-dirs
  "Returns the markdown graph directories in `dir`: `dir` itself when it has a
  pages directory, else its children that do"
  [dir]
  (if (fs/directory? (fs/path dir "pages"))
    [dir]
    (sort (map fs/parent (filter fs/directory? (fs/glob dir "*/pages"))))))

(defn- directory-graph-dirs
  "Returns the [[graph-dirs]] of a `--directory` option, erroring when there are none"
  [directory]
  (let [dir (fs/expand-home directory)
        dirs (when (fs/directory? dir) (graph-dirs dir))]
    (when (empty? dirs)
      (cli-util/error "No graph directories found in" (str dir)))
    dirs))

(defn- index-conflicts
  "Returns a conflict for each `:classes` or `:properties` key whose value
  differs across `graph->index`, a seq of `[graph index]` pairs. Keys set by
  the entry's `overrides` aren't compared, nor are entries they `::dissoc`"
  [index-name overrides graph->index]
  (for [section [:classes :properties]
        :let [removed (set (get-in overrides [section ::dissoc]))]
        [k graph-values] (group-by first (for [[graph index] graph->index
                                               [k v] (get index section)
                                               :when (not (removed k))]
                                           [k graph (apply dissoc v (keys (get-in overrides [section k])))]))
        :when (< 1 (count (set (map last graph-values))))]
    {:index index-name :section section :key k
     :values (into (sorted-map) (map (fn [[_ graph v]] [graph v])) graph-values)}))

(defn- override-section
  "Merges `section-overrides` entries over `entries`, then removes the entries
  listed in its `::dissoc`"
  [entries section-overrides]
  (apply dissoc
         (merge-with merge entries (dissoc section-overrides ::dissoc))
         (::dissoc section-overrides)))

(defn- merge-index [overrides indexes]
  (let [merged (reduce (fn [acc index]
                         (-> acc
                             (update :classes merge (:classes index))
                             (update :properties merge (:properties index))))
                       indexes)]
    (-> merged
        (update :classes override-section (:classes overrides))
        (update :properties override-section (:properties overrides)))))

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
  (let [dirs (directory-graph-dirs (:directory opts))]
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

(defn- my-property-key->ident
  "Qualifies a bare tagaholic-me-index.edn `:build/properties` key. `:url`
  becomes schema.org's url property since this index has none, and any other
  key becomes a tagaholic.me property"
  [k]
  (cond (qualified-keyword? k) k
        (= :url k) (schema-property-ident :url)
        :else (my-property-ident k)))

(defn- qualify-my-properties
  "Qualifies bare `:build/properties` keys in `m` (see [[my-property-key->ident]])"
  [m]
  (cond-> m
    (:build/properties m)
    (update :build/properties update-keys my-property-key->ident)))

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

(defn- uses-property?
  "Returns true if any of `new-classes` or `new-props` sets `:build/properties`
  key `k`"
  [index k new-classes new-props]
  (boolean (some #(contains? (get-in index (conj % :build/properties)) k)
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

(defn- error-if-missing-same-as
  "Errors if sameAs is `used?` but not `exists?` in the graph, since the
  import can only declare an existing schema.org property"
  [used? exists?]
  (when (and used? (not exists?))
    (cli-util/error ":schema.property/sameAs not found in graph. Run 'logseq-schema add sameAs' first")))

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

(defn- property-class-refs
  "Returns the class idents that properties `prop-ks` of `index` refer to:
  their `:build/property-classes` and domainIncludes values"
  [index prop-ks]
  (distinct (mapcat #(concat (get-in index [:properties % :build/property-classes])
                             (get-in index [:properties % :build/properties (schema-property-ident :domainIncludes)]))
                    prop-ks)))

(defn- local-class-key
  "Returns the index key of tagaholic.me class `ident`, or nil for any other class"
  [ident]
  (when (= my-class-ns (namespace ident)) (keyword (name ident))))

(defn- add-edn
  "Assembles the import EDN for `new-classes` and `new-props` plus the alias
  pages they need. Declares schema.org's url, schema.org's sameAs when
  `same-as?`, the existing meta property when `meta-anchor?`, and
  domainIncludes and the `existing` [[property-class-refs]] of `new-props`,
  since the import requires every used property and referred class declared"
  [index new-classes new-props {:keys [meta-anchor? same-as? existing]}]
  (let [{classes-map :entities class-pages :pages}
        (aliased-entities (:classes index) new-classes my-class-ident #(full-class-entry index %))
        classes-map (into classes-map
                          (comp (filter existing) (map (fn [ident] [ident {:block/title (name ident)}])))
                          (property-class-refs index new-props))
        {props-map :entities prop-pages :pages}
        (aliased-entities (:properties index) new-props my-property-ident #(full-property-entry index %))
        props-map (cond-> props-map
                    (or (seq classes-map) (seq props-map))
                    (assoc (schema-property-ident :url) (anchor-entry (:url schema-org-properties) "url"))
                    same-as?
                    (assoc (schema-property-ident :sameAs) (anchor-entry (:sameAs schema-org-properties) "sameAs"))
                    (uses-property? index (schema-property-ident :domainIncludes) new-classes new-props)
                    (assoc (schema-property-ident :domainIncludes)
                           (anchor-entry (:domainIncludes schema-org-properties) "domainIncludes"))
                    meta-anchor?
                    (assoc (my-property-ident :meta) (anchor-entry (get-in index [:properties :meta]) "meta")))
        pages (into class-pages prop-pages)]
    (cond-> {}
      (seq classes-map) (assoc :classes classes-map)
      (seq props-map) (assoc :properties props-map)
      (seq pages) (assoc :pages-and-blocks pages))))

(defn- build-add-edn
  "Builds import EDN for `arg-kws` plus the local classes they need, i.e.
  ancestors and [[property-class-refs]], not yet in `graph`. Errors if an arg
  already exists or a new class's schema.org parent or used sameAs property
  doesn't. The meta property and alias pages are added when an entry uses
  them. `added` idents count as existing since the same import adds them.
  Returns a map of `:new-classes`, `:new-props` and `:edn`"
  [index graph arg-kws added]
  (let [{:keys [classes properties]} index
        class-args (filter #(contains? classes %) arg-kws)
        prop-args (filter #(contains? properties %) arg-kws)
        class-refs (property-class-refs index prop-args)
        need-classes (closure index (concat class-args (keep local-class-key class-refs)))
        schema-url (schema-property-ident :url)
        same-as-ident (schema-property-ident :sameAs)
        meta-ident (my-property-ident :meta)
        existing (into (logseq-util/existing-idents graph
                                                    (into #{schema-url same-as-ident meta-ident}
                                                          (concat (schema-org-parents index need-classes)
                                                                  (map my-class-ident need-classes)
                                                                  (map my-property-ident prop-args)
                                                                  class-refs)))
                       added)
        new-classes (remove (comp existing my-class-ident) need-classes)
        arg-new-props (remove (comp existing my-property-ident) prop-args)
        meta? (and (not-any? #{:meta} arg-new-props) (uses-property? index :meta new-classes arg-new-props))
        new-props (cond-> arg-new-props (and meta? (not (existing meta-ident))) (concat [:meta]))
        same-as? (uses-property? index same-as-ident new-classes new-props)
        _ (error-if-duplicates graph
                               (distinct (concat class-args new-classes))
                               (distinct (concat prop-args new-props)))
        _ (when-not (existing schema-url)
            (cli-util/error ":schema.property/url not found in graph. Run 'logseq-schema init' first"))
        _ (error-if-missing-same-as same-as? (existing same-as-ident))
        _ (error-if-missing-schema-org-parents existing (schema-org-parents index new-classes))]
    {:new-classes new-classes
     :new-props new-props
     :edn (add-edn index new-classes new-props {:meta-anchor? (and meta? (existing meta-ident))
                                                :same-as? same-as?
                                                :existing existing})}))

(defn- error-if-not-in-my-index
  "Errors if any of `arg-kws` isn't a class or property in `index`"
  [index arg-kws]
  (when-let [unknown (seq (remove #(or (contains? (:classes index) %)
                                       (contains? (:properties index) %))
                                  arg-kws))]
    (cli-util/error "These are not classes or properties in tagaholic-me-index.edn:"
                    (pr-str (vec unknown)))))

(defn- schema-org-refs
  "Returns the schema.org idents tagaholic.me properties `my-props` refer to:
  domainIncludes when used and the schema.org classes in their
  [[property-class-refs]]"
  [my-index my-props]
  (let [domain-includes (schema-property-ident :domainIncludes)]
    (cond-> (filter #(= "schema.class" (namespace %)) (property-class-refs my-index my-props))
      (uses-property? my-index domain-includes [] my-props) (conj domain-includes))))

(defn- schema-requirements
  "Returns the schema.org idents that tagaholic.me `my-classes` and `my-props`
  need in the graph to be added: their classes' schema.org parents, url,
  sameAs when used and their [[schema-org-refs]]"
  [my-index my-classes my-props]
  (let [classes (closure my-index my-classes)
        same-as (schema-property-ident :sameAs)]
    (cond-> (into (schema-org-parents my-index classes) (schema-org-refs my-index my-props))
      (or (seq my-classes) (seq my-props)) (conj (schema-property-ident :url))
      (uses-property? my-index same-as classes my-props) (conj same-as))))

(defn- merge-import-edns
  "Merges import EDN maps, combining their `:classes` and `:properties` entries
  and concatenating their `:pages-and-blocks`"
  [edns]
  (apply merge-with (fn [x y] (if (map? x) (merge-with merge x y) (into x y))) edns))

(defn- add-schema
  "Adds class and property `args` to `:graph`, or prints their EDN when
  `:pretend` is set. The [[schema-org-refs]] of new properties that `:graph`
  doesn't have are added too, through
  [[cldwalker.bb-clis.bin.logseq-schema/add-edn]]"
  [args {:keys [graph pretend]}]
  (let [index (read-my-index)
        arg-kws (distinct (map keyword args))
        _ (error-if-not-in-my-index index arg-kws)
        refs (schema-org-refs index (filter #(contains? (:properties index) %) arg-kws))
        missing-refs (remove (logseq-util/existing-idents graph refs) refs)
        schema-add (when (seq missing-refs) (logseq-schema/add-edn graph (map name missing-refs)))
        my-add (build-add-edn index graph arg-kws
                              (set (concat (map schema-class-ident (:new-classes schema-add))
                                           (map schema-property-ident (:new-props schema-add)))))
        new-classes (concat (:new-classes schema-add) (:new-classes my-add))
        new-props (concat (:new-props schema-add) (:new-props my-add))
        edn (merge-import-edns [(:edn schema-add) (:edn my-add)])]
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

;; ---- update and schema-update ----

(def ^:private my-schema-org-index
  (some-> (io/resource "my-schema-org-index.edn") slurp edn/read-string))

(defn- read-my-schema-org-index []
  (or my-schema-org-index (cli-util/error "Unable to find my-schema-org-index.edn on classpath")))

(def ^:private type->index-keys
  {"all" [:classes :properties] "class" [:classes] "property" [:properties]})

(defn- schema-org-ident
  "Returns the graph ident of my-schema-org-index.edn key `k` under `index-key`.
  A qualified `k`, e.g. `:logseq.property/description`, is already an ident"
  [index-key k]
  (cond (qualified-keyword? k) k
        (= :classes index-key) (schema-class-ident k)
        :else (schema-property-ident k)))

(def ^:private schema-org-meta-key
  "my-schema-org-index.edn's `:build/properties` key for a meta note"
  :tagaholic.me/meta)

(defn- schema-org-property-key->ident
  "Returns the graph property ident of my-schema-org-index.edn
  `:build/properties` key `k`"
  [k]
  (if (= schema-org-meta-key k) (my-property-ident :meta) k))

(def ^:private schema-update-name-candidates
  "Completion candidates for `schema-update` args, described by their aliases
  or meta"
  (mapv (fn [[k v]]
          (let [desc (or (some->> (seq (:build/alias v)) (map name) (str/join ", ") (str "alias: "))
                         (get-in v [:build/properties schema-org-meta-key]))]
            (cond-> {:value (symbol (name k))}
              desc (assoc :description desc))))
        (concat (:classes my-schema-org-index) (:properties my-schema-org-index))))

(def ^:private update-attributes
  "Top-level index entry keys that update compares. They're built-in property
  schema attributes holding a scalar, e.g. a keyword, rather than a ref. An
  import ignores them as top-level `:properties` keys on an existing
  property, so they're updated through the property page's `:build/properties`"
  #{:logseq.property/ui-position})

(defn- update-target
  "Returns what [[build-update-edn]] compares for index `entry` `k`: the
  entity's graph `ident`, the entry's aliases, its `:build/properties` keyed by
  the graph property idents `key->ident` returns, and its
  [[update-attributes]]"
  [k ident entry key->ident]
  {:k k
   :ident ident
   :aliases (:build/alias entry)
   :properties (update-keys (or (:build/properties entry) {}) key->ident)
   :attributes (select-keys entry update-attributes)})

(defn- ident-attr-values
  "Runs `query`, which finds `[ident attr value]` given `:in` collections
  `idents` and `attrs`, returning `{ident {attr value}}`"
  [graph query idents attrs]
  (if (empty? attrs)
    {}
    (reduce (fn [m [ident attr v]] (assoc-in m [ident attr] v))
            {}
            (logseq-util/logseq-query graph query [(vec idents) (vec attrs)]))))

(defn- update-graph-state
  "Fetches the uuids and alias titles of `idents` in `graph`, plus the
  `property-idents` value titles and `attribute-idents` values of them.
  `:uuids` also has the meta and sameAs properties' uuids when they exist"
  [graph idents property-idents attribute-idents]
  {:uuids (logseq-util/idents->uuids graph (into (set idents) [(my-property-ident :meta) (schema-property-ident :sameAs)]))
   :aliases (reduce (fn [m [ident title]] (update m ident (fnil conj #{}) title))
                    {}
                    (logseq-util/logseq-query graph
                                              '[:find ?ident ?title :in $ [?ident ...]
                                                :where [?e :db/ident ?ident] [?e :block/alias ?a] [?a :block/title ?title]]
                                              [(vec idents)]))
   :values (ident-attr-values graph
                              '[:find ?ident ?p ?title :in $ [?ident ...] [?p ...]
                                :where [?e :db/ident ?ident] [?e ?p ?v] [?v :block/title ?title]]
                              idents property-idents)
   :attributes (ident-attr-values graph
                                  '[:find ?ident ?a ?v :in $ [?ident ...] [?a ...]
                                    :where [?e :db/ident ?ident] [?e ?a ?v]]
                                  idents attribute-idents)})

(defn- changed-values
  "Returns the entries of `m` whose values differ from `current`"
  [m current]
  (into {} (remove (fn [[k v]] (= v (get current k)))) m))

(defn- entity-update
  "Returns the `:pages` updating `target`'s entity (see [[update-target]])
  with the aliases, property values and attributes that differ from `state`,
  plus those changed `:properties`, or nil when none differ. Aliases are only
  added since an import can't remove them"
  [state {:keys [ident] :as target}]
  (let [current (set (map str/lower-case (get-in state [:aliases ident])))
        aliases (remove #(current (str/lower-case %)) (map name (:aliases target)))
        changed (merge (changed-values (:properties target) (get-in state [:values ident]))
                       (changed-values (:attributes target) (get-in state [:attributes ident])))
        {:keys [pages alias]} (alias-pages aliases)]
    (when (or (seq aliases) (seq changed))
      {:properties changed
       :pages (conj pages
                    ;; empty :build/properties forces the full page-update path so :block/alias sticks
                    {:page (cond-> {:block/uuid (get-in state [:uuids ident])
                                    :build/properties changed}
                             (seq aliases) (assoc :block/alias alias))})})))

(defn- property-declarations
  "Returns `:properties` declaring the url, sameAs and meta properties in
  `used`, which the import requires whenever they're used. A meta property not
  in the graph gets its full entry, which uses url"
  [my-index used meta-exists?]
  (let [url (schema-property-ident :url)
        same-as (schema-property-ident :sameAs)
        meta (my-property-ident :meta)
        new-meta? (and (used meta) (not meta-exists?))]
    (cond-> {}
      (or (used url) new-meta?) (assoc url (anchor-entry (:url schema-org-properties) "url"))
      (used same-as) (assoc same-as (anchor-entry (:sameAs schema-org-properties) "sameAs"))
      (used meta) (assoc meta (if new-meta?
                                (full-property-entry my-index :meta)
                                (anchor-entry (get-in my-index [:properties :meta]) "meta"))))))

(defn- build-update-edn
  "Builds import EDN updating the entities of `targets` (see
  [[update-target]]) whose aliases or property values differ from `state`
  (see [[update-graph-state]]). Errors if a change uses sameAs and it isn't
  in the graph. Returns target keys grouped as `:updated`, `:current` and
  `:missing`, plus `:new-props` and `:edn`"
  [my-index targets state]
  (let [results (for [{:keys [k ident] :as target} targets
                      :let [exists? (contains? (:uuids state) ident)
                            change (when exists? (entity-update state target))]]
                  {:k k :change change
                   :status (cond (not exists?) :missing change :updated :else :current)})
        status-ks (fn [status] (mapv :k (filter #(= status (:status %)) results)))
        changes (keep :change results)
        used (into #{} (mapcat (comp keys :properties)) changes)
        meta-exists? (contains? (:uuids state) (my-property-ident :meta))
        properties (property-declarations my-index used meta-exists?)]
    (error-if-missing-same-as (used (schema-property-ident :sameAs))
                              (contains? (:uuids state) (schema-property-ident :sameAs)))
    {:updated (status-ks :updated)
     :current (status-ks :current)
     :missing (status-ks :missing)
     :new-props (if (and (used (my-property-ident :meta)) (not meta-exists?)) [:meta] [])
     :edn (cond-> {}
            (seq changes) (assoc :pages-and-blocks (vec (mapcat :pages changes)))
            (seq properties) (assoc :properties properties))}))

(defn- run-update
  "Updates the entities of `targets` (see [[update-target]]) in `:graph`, or
  prints their EDN when `:pretend` is set. Ends with a summary of how many
  were updated, already current or missing from the graph"
  [targets {:keys [graph pretend]}]
  (let [state (update-graph-state graph
                                  (map :ident targets)
                                  (distinct (mapcat (comp keys :properties) targets))
                                  (distinct (mapcat (comp keys :attributes) targets)))
        {:keys [updated current missing new-props edn]} (build-update-edn (read-my-index) targets state)]
    (error-if-duplicates graph [] new-props)
    (cond
      pretend (pprint/pprint edn)
      (seq updated) (logseq-util/import-edn graph edn))
    (println (count updated) "to update," (count current) "exist and" (count missing) "don't exist")))

(defn- my-update-targets
  "Returns the [[update-target]]s for the classes and then properties of
  `index`, a tagaholic-me-index.edn, named by `arg-kws`, or all of them when
  there are no `arg-kws`"
  [index arg-kws]
  (let [selected? (if (seq arg-kws) (set arg-kws) any?)]
    (for [[index-key ident-fn] [[:classes my-class-ident] [:properties my-property-ident]]
          [k entry] (sort-by key (get index index-key))
          :when (selected? k)]
      (update-target k (ident-fn k) entry my-property-key->ident))))

(defn- my-update
  "Updates the tagaholic.me classes and properties named by `args`, or all of
  them, in `:graph` (see [[run-update]])"
  [args opts]
  (let [index (read-my-index)
        arg-kws (distinct (map keyword args))]
    (error-if-not-in-my-index index arg-kws)
    (run-update (my-update-targets index arg-kws) opts)))

(defn- update-command [{{:keys [args] :as opts} :opts}]
  (my-update args opts))

(def ^:private update-spec
  {:graph (:graph add-spec)
   :pretend {:alias :n :coerce :boolean
             :desc "Print the EDN to import and a summary instead of importing it"}
   :args {:coerce [:symbol]
          :complete my-name-candidates
          :desc "tagaholic-me-index.edn classes or properties to update"}})

(defn- select-schema-update-entries
  "Returns `[index-key k entry]` for the entries under `index-keys` of
  my-schema-org-index.edn named by `args`, or all of them when there are no
  `args`"
  [index-keys args]
  (let [index (read-my-schema-org-index)
        arg-names (set (map str args))
        entries (for [index-key index-keys
                      [k entry] (sort-by key (get index index-key))
                      :when (or (empty? arg-names) (arg-names (name k)))]
                  [index-key k entry])
        unknown (remove (set (map (comp name second) entries)) (distinct (map str args)))]
    (when (seq unknown)
      (cli-util/error (str "These are not " (str/join " or " (map name index-keys)) " in my-schema-org-index.edn:")
                      (pr-str (vec unknown))))
    entries))

(defn- schema-update
  "Updates the `:type` schema.org entities named by `args`, or all of them,
  in `:graph` (see [[run-update]])"
  [args {:keys [type] :as opts}]
  (run-update (for [[index-key k entry] (select-schema-update-entries (type->index-keys type) args)]
                (update-target k (schema-org-ident index-key k) entry schema-org-property-key->ident))
              opts))

(defn- schema-update-command [{{:keys [args] :as opts} :opts}]
  (schema-update args opts))

(def ^:private schema-update-spec
  {:graph (:graph add-spec)
   :pretend (:pretend update-spec)
   :type {:alias :t :enum ["all" "class" "property"] :default "all"
          :desc "Type of schema.org entities to update"}
   :args {:coerce [:symbol]
          :complete schema-update-name-candidates
          :desc "my-schema-org-index.edn classes or properties to update"}})

;; ---- import ----

(def ^:private ignored-page-properties
  "File graph built-in page properties that have nothing to import into a DB graph"
  #{"title" "id" "filters" "collapsed" "public" "icon" "created-at" "updated-at"
    "exclude-from-graph-view" "query-table" "query-properties" "query-sort-by" "query-sort-desc"})

(def ^:private tag-property-keys
  "Page properties whose values are the page's tags"
  ["type" "tags"])

(defn- lookup-key
  "Normalizes a class or property name for lookup, ignoring case like Logseq
  page names do and whitespace like [[safe-keyword]] does"
  [s]
  (str/lower-case (str/replace (name s) #"\s+" "")))

(defn- name-lookup
  "Maps the [[lookup-key]] of each unqualified key and `:build/alias` in
  `entries` to `[source key]`"
  [source entries]
  (into {}
        (comp (remove (comp qualified-keyword? key))
              (mapcat (fn [[k v]] (map (fn [n] [(lookup-key n) [source k]]) (cons k (:build/alias v))))))
        entries))

(defn- import-lookups
  "Maps class and property names, by [[lookup-key]], to the `[source key]` refs
  they import as. A source is `:schema`, `:tagaholic` or, for desc, `:logseq`.
  tagaholic.me names win over schema.org ones"
  [my-index]
  (let [my-schema-org (read-my-schema-org-index)]
    {:classes (merge (name-lookup :schema schema-org-classes)
                     (name-lookup :schema (:classes my-schema-org))
                     (name-lookup :tagaholic (:classes my-index)))
     :properties (merge (name-lookup :schema schema-org-properties)
                        (name-lookup :schema (:properties my-schema-org))
                        (name-lookup :tagaholic (:properties my-index))
                        {"desc" [:logseq :description]})}))

(defn- ref-ident
  "Returns the ident of a `[source key]` class or property ref"
  [kind [source k]]
  (case [kind source]
    [:classes :schema] (schema-class-ident k)
    [:classes :tagaholic] (my-class-ident k)
    [:properties :schema] (schema-property-ident k)
    [:properties :tagaholic] (my-property-ident k)
    [:properties :logseq] (keyword "logseq.property" (name k))))

(defn- ref-property-entry
  "Returns the index entry of a `[source key]` property ref"
  [my-index [source k]]
  (case source
    :schema (get schema-org-properties k)
    :tagaholic (get-in my-index [:properties k])
    :logseq {:logseq.property/type :default}))

(defn- split-values
  "Splits a property value into its comma-separated names, stripping any
  [[]] or # around each"
  [v]
  (->> (str/split v #",")
       (map #(str/replace (str/trim %) #"^#?\[\[|\]\]$|^#" ""))
       (remove str/blank?)))

(defn- journal-day
  "Parses a `M-d-yyyy` or `yyyy-M-d` date into a journal day int, or nil"
  [s]
  (when-let [[y m d] (if-let [[_ m d y] (re-matches #"(\d{1,2})-(\d{1,2})-(\d{4})" s)]
                       [y m d]
                       (next (re-matches #"(\d{4})-(\d{1,2})-(\d{1,2})" s)))]
    (+ (* 10000 (parse-long y)) (* 100 (parse-long m)) (parse-long d))))

(defn- property-value
  "Converts raw page property value `v` to a `:build/properties` value for
  property `entry`'s type and cardinality, or returns nil if it can't"
  [{prop-type :logseq.property/type cardinality :db/cardinality} v]
  (let [convert (fn [s]
                  (case prop-type
                    :node [:build/page {:block/title s}]
                    :date (some->> (journal-day s) (hash-map :build/journal) (vector :build/page))
                    :checkbox (parse-boolean s)
                    (:number :Number) (or (parse-long s) (parse-double s))
                    (:default :url) s
                    nil))]
    (if (= :many cardinality)
      (let [vs (map convert (split-values v))]
        (when (and (seq vs) (every? some? vs)) (set vs)))
      (some-> (if (= :node prop-type) (first (split-values v)) v) convert))))

(defn- resolve-properties
  "Resolves page properties `props` to `{:properties {ref value} :errors [msg]}`"
  [lookups my-index props]
  (reduce (fn [acc [k v]]
            (if-let [ref (get-in lookups [:properties (lookup-key k)])]
              (let [entry (ref-property-entry my-index ref)
                    value (property-value entry v)]
                (if (some? value)
                  (assoc-in acc [:properties ref] value)
                  (update acc :errors conj (str "Unable to convert " k ":: " v " to a "
                                                (name (:logseq.property/type entry)) " value"))))
              (update acc :errors conj (str "Unknown property " k))))
          {:properties {} :errors []}
          props))

(defn- resolve-page
  "Resolves a [[read-page]] page's tags and properties to the `[source key]`
  refs they import as. Returns `{:title :blocks :tags :properties :alias :errors}`"
  [lookups my-index {page-title :name :keys [props blocks]}]
  (if-let [ontology-type (#{"Class" "Property"} (get props "type"))]
    {:title page-title :errors [(str "Is a " ontology-type " page. Use add for it instead")]}
    (let [tags (map (fn [t] [t (get-in lookups [:classes (lookup-key t)])])
                    (mapcat split-values (keep props tag-property-keys)))
          {:keys [properties errors]}
          (resolve-properties lookups my-index
                              (apply dissoc props "alias" (concat tag-property-keys ignored-page-properties)))]
      {:title page-title
       :blocks blocks
       :tags (distinct (keep second tags))
       :properties properties
       :alias (some-> (get props "alias") split-values seq vec)
       :errors (into (mapv #(str "Unknown tag " (first %)) (remove second tags)) errors)})))

(defn- find-page
  "Reads page `page-arg` from `dirs`' graphs, matching file names
  case-insensitively. Errors if no graph has it or graphs disagree on its
  properties"
  [dirs page-arg]
  (let [found (for [dir dirs
                    path (fs/glob (fs/path dir "pages") "*.md")
                    :when (= (str/lower-case page-arg) (str/lower-case (page-name path)))]
                [(str (fs/file-name dir)) (read-page path)])]
    (cond
      (empty? found)
      (cli-util/error "Page" (pr-str page-arg) "not found in graphs:" (str/join ", " (map fs/file-name dirs)))
      (< 1 (count (distinct (map (comp :props second) found))))
      (cli-util/error "Page" (pr-str page-arg) "has different properties in graphs:" (str/join ", " (map first found)))
      :else (second (first found)))))

(defn- error-if-unresolved
  "Errors listing every resolved page's errors"
  [pages]
  (when-let [errors (seq (for [{:keys [title errors]} pages, e errors] (str "  " title ": " e)))]
    (cli-util/error (str/join "\n" (cons "Unable to import these pages:" errors)))))

(defn- page-entry
  "Builds a resolved page's `:pages-and-blocks` entries: its alias pages, then
  the page with its tags, properties and aliases"
  [{:keys [title tags properties alias]}]
  (let [{alias-page-entries :pages alias-refs :alias} (some-> alias alias-pages)]
    (conj (or alias-page-entries [])
          {:page (cond-> {:block/title title}
                   (seq tags) (assoc :build/tags (set (map #(ref-ident :classes %) tags)))
                   (seq properties) (assoc :build/properties (update-keys properties #(ref-ident :properties %)))
                   alias-refs (assoc :block/alias alias-refs))})))

(defn- pages-edn
  "Builds the import EDN for resolved `pages`, declaring each property they
  set since the import requires it, even for existing properties"
  [my-index pages]
  (let [declarations (into {}
                           (comp (mapcat (comp keys :properties))
                                 (distinct)
                                 (remove (comp #{:logseq} first))
                                 (map (fn [[_ k :as ref]]
                                        [(ref-ident :properties ref)
                                         (anchor-entry (ref-property-entry my-index ref) (name k))])))
                           pages)]
    (cond-> {:pages-and-blocks (into [] (mapcat page-entry) pages)}
      (seq declarations) (assoc :properties declarations))))

(defn- refs-of [source refs] (keep (fn [[s k]] (when (= source s) k)) refs))

(defn- build-import-edn
  "Builds one import EDN for resolved `pages` that also adds the tags and
  properties they use that aren't in `graph`: tagaholic.me ones through
  [[build-add-edn]], and schema.org ones plus the schema.org classes and
  properties new tagaholic.me ones need through
  [[cldwalker.bb-clis.bin.logseq-schema/add-edn]]. Returns a map of
  `:new-classes`, `:new-props` and `:edn`"
  [my-index graph pages]
  (let [tag-refs (distinct (mapcat :tags pages))
        prop-refs (remove (comp #{:logseq} first) (distinct (mapcat (comp keys :properties) pages)))
        required (schema-requirements my-index (refs-of :tagaholic tag-refs) (refs-of :tagaholic prop-refs))
        existing (logseq-util/existing-idents graph (concat (map #(ref-ident :classes %) tag-refs)
                                                            (map #(ref-ident :properties %) prop-refs)
                                                            required))
        new-tags (remove (comp existing #(ref-ident :classes %)) tag-refs)
        new-props (remove (comp existing #(ref-ident :properties %)) prop-refs)
        schema-names (distinct (map name (concat (refs-of :schema new-tags) (refs-of :schema new-props)
                                                 (remove existing required))))
        schema-add (when (seq schema-names) (logseq-schema/add-edn graph schema-names))
        my-kws (distinct (concat (refs-of :tagaholic new-tags) (refs-of :tagaholic new-props)))
        my-add (when (seq my-kws)
                 (build-add-edn my-index graph my-kws
                                (set (concat (map schema-class-ident (:new-classes schema-add))
                                             (map schema-property-ident (:new-props schema-add))))))]
    {:new-classes (concat (:new-classes schema-add) (:new-classes my-add))
     :new-props (concat (:new-props schema-add) (:new-props my-add))
     :edn (merge-import-edns [(:edn schema-add) (:edn my-add) (pages-edn my-index pages)])}))

(defn- print-imported [pages]
  (doseq [{:keys [title blocks]} pages]
    (println (str "Imported page '" title "'"
                  (when (pos? blocks)
                    (str ", skipping its " blocks " " (logseq-migrate/pluralize (range blocks) "block" "blocks")))))))

(defn- import-pages
  "Imports pages `args`, found in the graphs of `:directory`, into `:graph`
  with their tags and properties, or prints their EDN when `:pretend` is set"
  [args {:keys [graph pretend directory]}]
  (let [my-index (read-my-index)
        lookups (import-lookups my-index)
        dirs (directory-graph-dirs directory)
        pages (mapv #(resolve-page lookups my-index (find-page dirs %)) (distinct args))
        _ (error-if-unresolved pages)
        {:keys [new-classes new-props edn]} (build-import-edn my-index graph pages)]
    (println "Importing" (count pages) (logseq-migrate/pluralize pages "page" "pages") "and adding"
             (count new-classes) (logseq-migrate/pluralize new-classes "class" "classes") "and"
             (count new-props) (logseq-migrate/pluralize new-props "property" "properties") "-"
             (into (vec new-classes) new-props))
    (if pretend
      (pprint/pprint edn)
      (do (logseq-util/import-edn graph edn)
          (print-imported pages)))))

(defn- import-command [{{:keys [args] :as opts} :opts}]
  (when (empty? args)
    (cli-util/error "Usage: logseq-my-schema import [& PAGES]"))
  (import-pages args opts))

(def ^:private directory-option {:alias :d :default "~/code/priv"})

(def ^:private import-spec
  {:graph (:graph add-spec)
   :pretend (:pretend add-spec)
   :directory (assoc directory-option :desc "Directory of Logseq markdown graphs, or a single graph, to find pages in")
   :args {:coerce [:string] :desc "Pages to import"}})

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

(def ^:private ^:large-vars/data-var table
  [{:cmds ["build-index"]
    :fn build-index-command
    :spec {:directory (assoc directory-option :desc "Directory of Logseq markdown graphs, or a single graph, to read Property/Class pages from")}
    :doc (str "Build " index-path " and " my-schema-org-index-path
              " from the Property and Class pages of each graph in --directory.")}
   {:cmds ["add"]
    :fn add-command
    :spec add-spec
    :args->opts (repeat :args)
    :doc "Import classes or properties from tagaholic-me-index.edn that aren't
  in the graph yet. A class's local ancestors are included too; schema.org
  ancestors must already be in the graph (see logseq-schema add)."}
   {:cmds ["import"]
    :fn import-command
    :spec import-spec
    :args->opts (repeat :args)
    :doc "Import pages from the graphs in --directory with their tags and
  properties but not their blocks. Tags and properties not in the graph yet
  are added from tagaholic-me-index.edn or schema.org."}
   {:cmds ["update"]
    :fn update-command
    :spec update-spec
    :args->opts (repeat :args)
    :doc "Update the tagaholic.me classes and properties in the graph whose
  aliases or properties, e.g. url and sameAs, differ from
  tagaholic-me-index.edn. Updates all of them unless given names. Aliases are
  only added, never removed."}
   {:cmds ["schema-update"]
    :fn schema-update-command
    :spec schema-update-spec
    :args->opts (repeat :args)
    :doc "Update the schema.org classes and properties in the graph whose
  aliases or meta differ from my-schema-org-index.edn. Updates all of
  --type's entities unless given names. Aliases are only added, never
  removed."}
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
