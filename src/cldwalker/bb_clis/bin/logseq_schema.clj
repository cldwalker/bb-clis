(ns cldwalker.bb-clis.bin.logseq-schema
  "Manage schema.org classes and properties in a Logseq graph. Reads
  sqlite.build EDN from the classpath resource `schema-org-index.edn` and
  builds graph-ontology EDN that `logseq graph import --type edn` accepts."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def ^:private index
  "schema-org-index.edn parsed once from the classpath (nil if not found)"
  (some-> (io/resource "schema-org-index.edn") slurp edn/read-string))

(defn- read-index []
  (or index (cli-util/error "Unable to find schema-org-index.edn on classpath")))

(def ^:private schema-name-candidates
  "`add` arg completion candidates in babashka.cli's {:value :description} form -
  each schema.org class/property name as a symbol :value with its description.
  Whitespace is collapsed to single spaces so the whole description stays on one
  line: babashka.cli truncates a description at its first newline, which would
  otherwise drop the rest from completers that match against the description."
  (mapv (fn [[k v]]
          (let [desc (get-in v [:build/properties :logseq.property/description])]
            (cond-> {:value (symbol (name k))}
              desc (assoc :description (str/trim (str/replace desc #"\s+" " "))))))
        (concat (:classes index) (:properties index))))

(defn- class-ident [k] (keyword "schema.class" (name k)))
(defn- property-ident [k] (keyword "schema.property" (name k)))

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

(defn- existing-idents
  "Returns the subset of the given idents that exist in the graph"
  [graph idents]
  (if (empty? idents)
    #{}
    (set (logseq-query graph
                       '[:find [?ident ...] :in $ [?ident ...] :where [?e :db/ident ?ident]]
                       [(vec idents)]))))

(defn- existing-property-titles
  "Returns the subset of the given titles that already exist as a property in the
  graph. Checks by title, not :db/ident, since importing a property whose title
  matches an existing one under a different ident creates an unusable duplicate."
  [graph titles]
  (if (empty? titles)
    #{}
    (set (logseq-query graph
                       '[:find [?title ...] :in $ [?title ...]
                         :where
                         [?e :block/title ?title]
                         [?e :block/tags :logseq.class/Property]]
                       [(vec titles)]))))

(defn- existing-user-class-titles
  "Returns the subset of the given titles that already exist as a user class in
  the graph. Only considers user classes (no :logseq.property/built-in?) so that
  schema.org classes can coexist with same-named built-ins e.g. Comment. Checks
  by title since importing a class whose title matches an existing user class
  under a different ident creates an unusable duplicate."
  [graph titles]
  (if (empty? titles)
    #{}
    (set (logseq-query graph
                       '[:find [?title ...] :in $ [?title ...]
                         :where
                         [?e :block/title ?title]
                         [?e :block/tags :logseq.class/Tag]
                         (not [?e :logseq.property/built-in? _])]
                       [(vec titles)]))))

(defn- qualify-build-properties
  "Qualifies shorthand schema.org property keys used in :build/properties"
  [m]
  (cond-> m
    (:build/properties m)
    (update :build/properties update-keys #(if (qualified-keyword? %) % (property-ident %)))))

(defn- normalize-cardinality [m]
  (assoc m :db/cardinality
         (if (= :many (:db/cardinality m)) :db.cardinality/many :db.cardinality/one)))

(defn- full-class-entry
  "Builds a class entry without :build/class-properties as we don't want a class
  addition to pull in its properties"
  [index k]
  (let [v (dissoc (get-in index [:classes k]) :build/class-properties)]
    (cond-> (qualify-build-properties v)
      (:build/class-extends v)
      (update :build/class-extends #(set (map class-ident %))))))

(defn- full-property-entry
  "Builds a property entry, qualifying its :build/property-classes refs. Those
  classes are pulled into the emitted closure so they always resolve on import."
  [index k]
  (let [v (get-in index [:properties k])
        property-classes (set (map class-ident (:build/property-classes v)))]
    (cond-> (-> (qualify-build-properties v)
                (assoc :block/title (name k))
                normalize-cardinality
                (dissoc :build/property-classes))
      (seq property-classes)
      (assoc :build/property-classes property-classes))))

(defn- anchor-property-entry
  "Minimal entry for an existing property that must still be declared (only :url,
  used by every entity's :build/properties). Type and cardinality are included as
  the import validates them against the graph's property."
  [index k]
  (-> (get-in index [:properties k])
      (select-keys [:logseq.property/type :db/cardinality])
      (assoc :block/title (name k))
      normalize-cardinality))

(defn- closure
  "Given the class and property args, returns `{:classes #{} :properties #{}}` of
  every class and property that must be emitted. Walks references transitively:
  a class pulls in its :build/class-extends parents, a property pulls in its
  :build/property-classes, and :url is always included since every entity's
  :build/properties uses it."
  [index class-args prop-args]
  (loop [need-classes #{}
         need-props #{}
         class-queue (vec class-args)
         prop-queue (into [:url] prop-args)]
    (cond
      (seq class-queue)
      (let [c (first class-queue)
            rest-queue (subvec class-queue 1)]
        (cond
          (need-classes c) (recur need-classes need-props rest-queue prop-queue)
          (not (contains? (:classes index) c)) (cli-util/error "Class" c "not found in index")
          :else (recur (conj need-classes c) need-props
                       (into rest-queue (get-in index [:classes c :build/class-extends]))
                       prop-queue)))

      (seq prop-queue)
      (let [p (first prop-queue)
            rest-queue (subvec prop-queue 1)]
        (cond
          (need-props p) (recur need-classes need-props class-queue rest-queue)
          (not (contains? (:properties index) p)) (cli-util/error "Property" p "not found in index")
          :else (recur need-classes (conj need-props p)
                       (into class-queue (get-in index [:properties p :build/property-classes]))
                       rest-queue)))

      :else {:classes need-classes :properties need-props})))

(defn- error-if-duplicates
  "Any class or property being added - an explicit arg or a new entity
pulled into the closure - that collides by title with an existing user
class or property would create an unusable duplicate on import"
  [graph check-classes check-props]
  (let [dup-class-title? (existing-user-class-titles graph (map name check-classes))
        dup-prop-title? (existing-property-titles graph (map name check-props))
        dups (concat (filter (comp dup-class-title? name) check-classes)
                     (filter (comp dup-prop-title? name) check-props))]
    (when (seq dups)
      (cli-util/error
       (str "Cannot add these classes or properties which already exist: "
            (str/join ", " (map #(str "'" (name %) "'") dups)))))))

(defn- build-add-edn
  "Builds graph-ontology EDN for the given class and property args as well as
  any dependent properties and classes.  Existing entities are omitted, except
  the few the import cannot resolve on its own: :url (every entity's
  :build/properties uses it) and existing classes referenced by an emitted
  property's :build/property-classes both get minimal anchor entries"
  [index graph arg-kws]
  (let [{:keys [classes properties]} index
        class-args (filter #(contains? classes %) arg-kws)
        prop-args (filter #(contains? properties %) arg-kws)
        {need-classes :classes need-props :properties} (closure index class-args prop-args)
        existing (existing-idents graph
                                  (concat (map class-ident need-classes)
                                          (map property-ident need-props)))
        new-classes (remove (comp existing class-ident) need-classes)
        new-props (remove (comp existing property-ident) need-props)
        _ (error-if-duplicates graph
                               (distinct (concat class-args new-classes))
                               (distinct (concat prop-args new-props)))
        ;; Existing classes referenced by an emitted property's :build/property-classes
        ;; must stay in the map (the import can't resolve them by ident otherwise);
        ;; existing classes referenced only via :build/class-extends are dropped.
        anchor-classes (->> new-props
                            (mapcat #(get-in properties [% :build/property-classes]))
                            distinct
                            (filter (comp existing class-ident)))
        ;; The only existing property that must still be declared is :url
        anchor-props (filter (comp existing property-ident) need-props)
        classes-map (merge (into {} (map (fn [k] [(class-ident k) (full-class-entry index k)]) new-classes))
                           (into {} (map (fn [k] [(class-ident k) {:block/title (name k)}]) anchor-classes)))
        props-map (merge (into {} (map (fn [k] [(property-ident k) (full-property-entry index k)]) new-props))
                         (into {} (map (fn [k] [(property-ident k) (anchor-property-entry index k)]) anchor-props)))]
    {:new-classes new-classes
     :new-props new-props
     :edn (cond-> {}
            (seq classes-map) (assoc :classes classes-map)
            (seq props-map) (assoc :properties props-map))}))

(defn- import-edn [graph edn-map]
  (let [temp-file (fs/create-temp-file {:prefix "logseq-schema" :suffix ".edn"})]
    (try
      (spit (fs/file temp-file) (pr-str edn-map))
      (let [{:keys [exit]} (apply shell {:continue true} "logseq" "graph" "import"
                                  (concat (graph-args graph) ["--type" "edn" "--input" (str temp-file)]))]
        (when-not (zero? exit)
          (cli-util/error "Import failed with exit code" exit)))
      (finally (fs/delete-if-exists temp-file)))))

(defn- pluralize [coll singular plural]
  (if (= (count coll) 1) singular plural))

(defn- add-schema
  "Adds the given class/property args to the graph or, when pretend, prints the EDN"
  [args {:keys [graph pretend]}]
  (let [index (read-index)
        arg-kws (distinct (map keyword args))
        unknown (remove #(or (contains? (:classes index) %)
                             (contains? (:properties index) %))
                        arg-kws)
        _ (when (seq unknown)
            (cli-util/error "These are not properties or classes in schema.org:" (pr-str (vec unknown))))
        {:keys [new-classes new-props edn]} (build-add-edn index graph arg-kws)]
    (println "Adding" (count new-classes) (pluralize new-classes "class" "classes") "and"
             (count new-props) (pluralize new-props "property" "properties") "-"
             (into (vec new-classes) new-props))
    (if pretend
      (pprint/pprint edn)
      (import-edn graph edn))))

(defn- add-command [{{:keys [args] :as opts} :opts}]
  (when (empty? args)
    (cli-util/error "Usage: logseq-schema add [& PROPERTY-OR-CLASSES]"))
  (add-schema args opts))

;; ---- migrate ----

(defn- and-join
  "Joins names in English list form e.g. `a, b and c`"
  [names]
  (case (count names)
    0 ""
    1 (first names)
    (str (str/join ", " (butlast names)) " and " (last names))))

(defn- conflicts
  "User classes and properties whose title matches a schema.org name, returned as
  `{:classes [{:name :ident :uuid}] :properties [{:name :ident :uuid :type}]}`.
  Only user entities (no :logseq.property/built-in?) are considered."
  [graph index]
  (let [class-names (set (map name (keys (:classes index))))
        prop-names (set (map name (keys (:properties index))))
        user-classes (logseq-query graph
                                    '[:find ?title ?ident ?uuid
                                      :where
                                      [?e :block/tags :logseq.class/Tag]
                                      [?e :block/title ?title] [?e :db/ident ?ident] [?e :block/uuid ?uuid]
                                      (not [?e :logseq.property/built-in? _])]
                                    nil)
        user-props (logseq-query graph
                                  '[:find ?title ?ident ?uuid ?type
                                    :where
                                    [?e :block/tags :logseq.class/Property]
                                    [?e :block/title ?title] [?e :db/ident ?ident] [?e :block/uuid ?uuid]
                                    [?e :logseq.property/type ?type]
                                    (not [?e :logseq.property/built-in? _])]
                                  nil)]
    {:classes (->> user-classes
                   (filter (fn [[t]] (class-names t)))
                   (map (fn [[t i u]] {:name t :ident i :uuid u}))
                   (sort-by :name) vec)
     :properties (->> user-props
                      (filter (fn [[t]] (prop-names t)))
                      (map (fn [[t i u ty]] {:name t :ident i :uuid u :type ty}))
                      (sort-by :name) vec)}))

;; Property value storage by type (see logseq.db.frontend.property.type):
;; entity refs (:node/:date/:asset) point at a real entity; value-block refs
;; (:default/:url via :block/title, :number via :logseq.property/value) point at a
;; value block; everything else (e.g. :checkbox) is a scalar stored on the entity.
(def ^:private entity-ref-property-types #{:node :date :asset})
(def ^:private value-block-property-types #{:default :url :number})

(defn- ref-property-type? [type]
  (or (entity-ref-property-types type) (value-block-property-types type)))

(defn- entity-pull
  "Pull spec for an associated entity - its structure plus, for a property, the
  value(s). Value-block/entity-ref properties pull a nested map; scalars pull the
  value directly."
  [ident type]
  (cond-> [:db/id :block/uuid :block/name :block/title {:block/page [:block/uuid]}]
    (and ident (ref-property-type? type)) (conj {ident [:block/uuid :block/title :logseq.property/value]})
    (and ident (not (ref-property-type? type))) (conj ident)))

(defn- property-value
  "Settable value read from one original value. Entity refs reference by uuid,
  value blocks use :block/title (default/url) or :logseq.property/value (number),
  scalars are used directly."
  [type value]
  (cond
    (entity-ref-property-types type) [:block/uuid (:block/uuid value)]
    (value-block-property-types type) (or (:block/title value) (:logseq.property/value value))
    :else value))

(defn- schema-property-value
  "Value for a schema property from the original value(s). Uses a set for :many
  (the sqlite.build form for multi-valued properties) and a bare value otherwise."
  [index prop-name type raw]
  (let [values (cond (nil? raw) [] (map? raw) [raw] (coll? raw) (vec raw) :else [raw])
        settable (map #(property-value type %) values)]
    (if (= :many (get-in index [:properties (keyword prop-name) :db/cardinality]))
      (set settable)
      (first settable))))

(defn- entity-block-info
  "Structure needed to update an existing block via import edn. Grouping under the
  block's page plus its uuid+title is enough - the importer updates the existing
  block in place, so its parent and order are left untouched."
  [e]
  {:page-uuid (get-in e [:block/page :block/uuid])
   :title (:block/title e)})

(defn- collect-migrations
  "Queries all entities associated with the conflicting classes and properties and
  returns `{:entities {id ops} :class-counts {name n} :prop-counts {name n}}` where
  ops is `{:uuid :page? :block :add-tags :remove-tags :add-props :remove-props}`
  aggregating every change for that entity. Values are read now, before any mutation."
  [graph index {:keys [classes properties]}]
  (let [entities (atom {})
        class-counts (atom {})
        prop-counts (atom {})
        update-entity (fn [e f]
                        (let [page? (nil? (:block/page e))]
                          (swap! entities update (:db/id e)
                                 #(-> (or % {:uuid (:block/uuid e) :page? page?
                                             :add-tags [] :remove-tags [] :add-props {} :remove-props []})
                                      (cond-> (not page?) (assoc :block (entity-block-info e)))
                                      f))))]
    (doseq [{:keys [name ident]} classes]
      (let [ents (map first (logseq-query graph
                                          [:find (list 'pull '?e (entity-pull nil nil))
                                           :where ['?e :block/tags ident]]
                                          nil))]
        (swap! class-counts assoc name (count ents))
        (doseq [e ents]
          (update-entity e (fn [m] (-> m
                                       (update :add-tags conj (class-ident (keyword name)))
                                       (update :remove-tags conj ident)))))))
    (doseq [{:keys [name ident type]} properties]
      (let [ents (map first (logseq-query graph
                                          [:find (list 'pull '?e (entity-pull ident type))
                                           :where ['?e ident '_]]
                                          nil))]
        (swap! prop-counts assoc name (count ents))
        (doseq [e ents]
          (let [v (schema-property-value index name type (get e ident))]
            (update-entity e (fn [m] (-> m
                                         (update :add-props assoc (property-ident (keyword name)) v)
                                         (update :remove-props conj ident))))))))
    {:entities @entities :class-counts @class-counts :prop-counts @prop-counts}))

(defn- print-migrate-pretend [{:keys [classes properties]} entities class-counts prop-counts]
  (let [pnames (sort (map :name properties))
        cnames (sort (map :name classes))]
    (println "Migrate would rename:")
    (println "-" (count pnames) "properties:" (and-join pnames))
    (println "-" (count cnames) "classes:" (and-join cnames))
    (println)
    (println (count entities) "entities associated"
             (+ (apply + (vals prop-counts)) (apply + (vals class-counts)))
             "times to the following properties and classes:")
    (doseq [n pnames] (println (str "  " n " - " (prop-counts n))))
    (doseq [n cnames] (println (str "  " n " - " (class-counts n))))))

(defn- rename-originals-edn
  "EDN that renames each original class/property page to a temp name so its
  schema.org replacement can take the original name. An empty :build/properties is
  required: for an existing page the importer takes a \"minimal update\" shortcut
  that emits only the uuid and timestamps (dropping the new title) UNLESS the page
  carries :build/properties or :build/tags. The empty map forces the full path
  without writing any property. (:build/keep-uuid? does NOT work here - it routes
  to the create-new-page path, which does not rename the existing entity.)"
  [{:keys [classes properties]}]
  {:pages-and-blocks
   (vec (for [{:keys [name uuid]} (concat classes properties)]
          {:page {:block/uuid uuid
                  :block/title (str "logseq-schema." name)
                  :build/properties {}}}))})

(defn- schema-property-declaration
  "Minimal declaration for a schema property so the import's undeclared-property
  check passes; type and cardinality match the graph's existing property."
  [index name]
  (let [p (get-in index [:properties (keyword name)])]
    {:block/title name
     :logseq.property/type (:logseq.property/type p)
     :db/cardinality (if (= :many (:db/cardinality p)) :db.cardinality/many :db.cardinality/one)}))

(defn- extending-classes
  "User classes - other than the conflicts themselves - that extend a conflict
  class, as `{uuid #{schema-class-idents}}` of the schema.org classes they should
  now extend. The migration adds these so the relationship isn't lost when the
  originals (and their extends) are removed."
  [graph {:keys [classes]}]
  (let [conflict-idents (set (map :ident classes))
        ident->schema (into {} (map (fn [{:keys [ident name]}] [ident (class-ident (keyword name))])) classes)
        rows (when (seq conflict-idents)
               (logseq-query graph
                             '[:find ?child-ident ?child-uuid ?parent-ident
                               :in $ [?parent-ident ...]
                               :where
                               [?child :logseq.property.class/extends ?p]
                               [?p :db/ident ?parent-ident]
                               [?child :db/ident ?child-ident]
                               [?child :block/uuid ?child-uuid]]
                             [(vec conflict-idents)]))]
    (->> rows
         (remove (fn [[child-ident _ _]] (conflict-idents child-ident)))
         (group-by second)
         (into {} (map (fn [[child-uuid child-rows]]
                         [child-uuid (set (map (fn [[_ _ pident]] (ident->schema pident)) child-rows))]))))))

(defn- conflict-aliases
  "Map of schema.org replacement :db/ident -> #{alias-target uuids}, read from the
  original conflict class/property pages so their :block/alias can be moved to the
  replacements before the originals are removed."
  [graph {:keys [classes properties]}]
  (let [ident->schema (into {} (concat
                                (map (fn [{:keys [ident name]}] [ident (class-ident (keyword name))]) classes)
                                (map (fn [{:keys [ident name]}] [ident (property-ident (keyword name))]) properties)))
        rows (when (seq ident->schema)
               (logseq-query graph
                             '[:find ?ident ?alias-uuid
                               :in $ [?ident ...]
                               :where
                               [?e :db/ident ?ident]
                               [?e :block/alias ?a]
                               [?a :block/uuid ?alias-uuid]]
                             [(vec (keys ident->schema))]))]
    (reduce (fn [m [ident auuid]] (update m (ident->schema ident) (fnil conj #{}) auuid)) {} rows)))

(defn- idents->uuids
  "Maps each :db/ident to its :block/uuid"
  [graph idents]
  (if (empty? idents)
    {}
    (into {} (logseq-query graph
                           '[:find ?ident ?uuid :in $ [?ident ...]
                             :where [?e :db/ident ?ident] [?e :block/uuid ?uuid]]
                           [(vec idents)]))))

(defn- migrate-add-edn
  "Builds one import-edn map that adds tags and properties and copies property values to entities
   associated with changing properties and tags. Also handles aliases for onto entities
   and extends for classes"
  [index {:keys [properties]} entities extends-by-uuid aliases]
  (let [prop-decls (into {} (map (fn [{:keys [name]}]
                                   [(property-ident (keyword name)) (schema-property-declaration index name)]))
                         properties)
        add (fn [m {:keys [add-tags add-props]}]
              (cond-> m
                (seq add-tags) (assoc :build/tags (vec add-tags))
                (seq add-props) (assoc :build/properties add-props)))
        with-extends (fn [m uuid]
                       (cond-> m
                         (extends-by-uuid uuid)
                         (assoc-in [:build/properties :logseq.property.class/extends] (extends-by-uuid uuid))))
        ops (vals entities)
        page-uuids (set (map :uuid (filter :page? ops)))
        page-entries (for [{:keys [uuid page?] :as op} ops :when page?]
                       {:page (-> (add {:block/uuid uuid} op) (with-extends uuid))})
        ;; extending classes that aren't already updated as page entities above
        extender-entries (for [uuid (keys extends-by-uuid) :when (not (page-uuids uuid))]
                           {:page (with-extends {:block/uuid uuid} uuid)})
        ;; move each original's :block/alias onto its schema replacement (empty
        ;; :build/properties forces the full page-update path so :block/alias sticks)
        alias-entries (for [[uuid targets] aliases]
                        {:page {:block/uuid uuid
                                :block/alias (set (map (fn [u] [:block/uuid u]) targets))
                                :build/properties {}}})
        block-entries (for [[page-uuid page-ops] (group-by #(get-in % [:block :page-uuid])
                                                           (remove :page? ops))]
                        {:page {:block/uuid page-uuid}
                         :blocks (vec (for [{:keys [uuid block] :as op} page-ops]
                                        (add {:block/uuid uuid :block/title (:title block)} op)))})]
    (cond-> {:pages-and-blocks (vec (concat page-entries extender-entries alias-entries block-entries))}
      (seq prop-decls) (assoc :properties prop-decls))))

(defn- remove-user-associations
  "Removes the original user tags and property values from each entity with one
  `logseq upsert page|block --remove-tags/--remove-properties` per entity (import
  edn cannot retract)."
  [graph entities]
  (doseq [[id {:keys [page? remove-tags remove-props]}] entities]
    (let [args (concat ["logseq" "upsert" (if page? "page" "block")]
                       (graph-args graph)
                       ["--id" (str id)]
                       (when (seq remove-tags) ["--remove-tags" (pr-str (vec remove-tags))])
                       (when (seq remove-props) ["--remove-properties" (pr-str (vec remove-props))]))
          {:keys [exit out err]} (apply shell {:continue true :out :string :err :string} args)]
      (when-not (zero? exit)
        (cli-util/error "Failed to remove user associations from entity" id "-" (str/trim (str out err)))))))

(defn- association-count
  "Number of entities still associated with an original class (by tag) or property"
  [graph {:keys [ident type]}]
  (or (logseq-query graph
                    (if type
                      [:find (list 'count '?e) '. :where ['?e ident '_]]
                      [:find (list 'count '?e) '. :where ['?e :block/tags ident]])
                    nil)
      0))

(defn- original-db-ids
  "Maps each original :db/ident to its current :db/id"
  [graph idents]
  (into {}
        (map (fn [e] [(:db/ident e) (:db/id e)]))
        (map first (logseq-query graph
                                 '[:find (pull ?e [:db/id :db/ident]) :in $ [?ident ...]
                                   :where [?e :db/ident ?ident]]
                                 [(vec idents)]))))

(defn- remove-originals
  "Removes the original class and property pages (values already migrated off them)"
  [graph {:keys [classes properties]}]
  (let [ids (original-db-ids graph (map :ident (concat classes properties)))
        remove-page (fn [kind {:keys [ident name]}]
                      (let [{:keys [exit out err]}
                            (apply shell {:continue true :out :string :err :string}
                                   (concat ["logseq" "remove" kind] (graph-args graph)
                                           ["--id" (str (ids ident))]))]
                        (when-not (zero? exit)
                          (cli-util/error "Failed to remove original" name "-" (str/trim (str out err))))))]
    (doseq [p properties] (remove-page "property" p))
    (doseq [c classes] (remove-page "tag" c))))

(defn- migrate* [graph index {:keys [properties classes] :as conflicts} entities]
  (println "Migrating" (count properties) (pluralize properties "property" "properties")
           "and" (count classes) (pluralize classes "class" "classes")
           "across" (count entities) (pluralize entities "entity" "entities") "...")
  (println "Properties:" (and-join (sort (map :name properties))))
  (println "Classes:" (and-join (sort (map :name classes))))

  ;; Relationships to move onto the replacements, captured before the originals are removed
  (let [extends-by-uuid (extending-classes graph conflicts)
        schema-ident->aliases (conflict-aliases graph conflicts)]
    ;; Step 2: free the original names by renaming the originals
    (import-edn graph (rename-originals-edn conflicts))
    ;; Step 3: create the schema.org replacements under the original names
    (add-schema (mapv :name (concat classes properties)) {:graph graph :pretend false})
    ;; Step 4: add the schema tags and copied property values onto the entities,
    ;; re-point extending classes, and move aliases onto the schema.org replacements
    (let [schema-ident->uuid (idents->uuids graph (keys schema-ident->aliases))
          aliases (into {} (map (fn [[sident targets]] [(schema-ident->uuid sident) targets]))
                        schema-ident->aliases)]
      (import-edn graph (migrate-add-edn index conflicts entities extends-by-uuid aliases))))
  ;; Step 5: remove the original user tags and property values from the entities
  (remove-user-associations graph entities)
  ;; Step 6: confirm nothing is still associated, then remove the originals
  (let [remaining (->> (concat properties classes)
                       (map (fn [c] [(:name c) (association-count graph c)]))
                       (filter (comp pos? second)))]
    (when (seq remaining)
      (cli-util/error "Migration incomplete; entities still associated with:"
                      (pr-str (into {} remaining))))
    (remove-originals graph conflicts))
  (println "Migration complete."))

(defn- migrate [{:keys [graph pretend]}]
  (let [index (read-index)
        conflicts (conflicts graph index)
        {:keys [classes properties]} conflicts
        ;; Step 1
        {:keys [entities class-counts prop-counts]} (collect-migrations graph index conflicts)]
    (cond
      (and (empty? classes) (empty? properties))
      (println "No user classes or properties found to migrate.")

      pretend
      #_:clj-kondo/ignore
      (do (print-migrate-pretend conflicts entities class-counts prop-counts)
          #_(clojure.pprint/pprint (rename-originals-edn conflicts))
          #_(clojure.pprint/pprint (migrate-add-edn index conflicts entities)))

      :else
      (migrate* graph index conflicts entities))))

(defn- migrate-command [{:keys [opts]}]
  (migrate opts))

(defn- init-command [{:keys [opts]}]
  (add-schema ["Thing" "url"] opts))

(def ^:private add-spec
  {:graph {:alias :g :desc "Graph name"}
   :pretend {:alias :n :coerce :boolean
             :desc "Print the resulting EDN instead of importing it"}})

(def ^:private add-command-spec
  "add's spec plus the positional :args, whose :complete tab-completes schema.org
  properties and classes with their descriptions"
  (assoc add-spec :args {:coerce [:symbol]
                         :complete schema-name-candidates
                         :desc "schema.org classes or properties to add"}))

(def ^:private table
  [{:cmds ["add"]
    :fn add-command
    :spec add-command-spec
    :args->opts (repeat :args)
    :doc "Import schema.org classes or properties that aren't in the graph.
  Classes include their ancestor classes but not their class properties.
  Imports by default but use --pretend to see what would be imported."}
   {:cmds ["init"]
    :fn init-command
    :spec add-spec
    :doc "Add base class and property that schema.org depends on."}
   {:cmds ["migrate"]
    :fn migrate-command
    :spec add-spec
    :doc "Replace user classes and properties that conflict with schema.org names
  with their schema.org equivalents, moving tags and property values from the
  originals onto the replacements. Use --pretend to preview what would change."}])

(defn -main [& args]
  (cli/dispatch table args {:prog "logseq-schema" :help true}))
