(ns cldwalker.bb-clis.util.logseq-migrate
  "Migrates user classes and properties whose titles match an index's names
  onto the index's entities, moving tags, property values, extends and aliases
  from the originals onto the replacements before removing the originals.

  Callers describe their index with a config map of:
  * `:index` - sqlite.build EDN with `:classes` and `:properties`
  * `:class-ident`/`:property-ident` - fns of an index key to its graph ident
  * `:add-schema` - fn of entry names and `{:graph :pretend}` that imports them
  * `:rename-prefix` - prefix for renaming originals out of the way"
  (:require [babashka.process :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.logseq :as logseq-util]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn pluralize
  "Returns `singular` when `coll` has one item, else `plural`"
  [coll singular plural]
  (if (= (count coll) 1) singular plural))

(defn- and-join
  "Joins names in English list form e.g. `a, b and c`"
  [names]
  (case (count names)
    0 ""
    1 (first names)
    (str (str/join ", " (butlast names)) " and " (last names))))

(defn- user-ident?
  "Returns true if `ident` is a user class or property, as opposed to one
  already from an index like tagaholic.me or schema.org"
  [ident]
  (contains? #{"user.class" "user.property"} (namespace ident)))

(defn- conflicts
  "User classes and properties whose title matches an index name, returned as
  `{:classes [{:name :ident :uuid}] :properties [{:name :ident :uuid :type}]}`.
  Only non built-in entities with a [[user-ident?]] are considered."
  [graph {:keys [index]}]
  (let [class-names (set (map name (keys (:classes index))))
        prop-names (set (map name (keys (:properties index))))
        user-classes (logseq-util/logseq-query graph
                                               '[:find ?title ?ident ?uuid
                                                 :where
                                                 [?e :block/tags :logseq.class/Tag]
                                                 [?e :block/title ?title] [?e :db/ident ?ident] [?e :block/uuid ?uuid]
                                                 (not [?e :logseq.property/built-in? _])]
                                               nil)
        user-props (logseq-util/logseq-query graph
                                             '[:find ?title ?ident ?uuid ?type
                                               :where
                                               [?e :block/tags :logseq.class/Property]
                                               [?e :block/title ?title] [?e :db/ident ?ident] [?e :block/uuid ?uuid]
                                               [?e :logseq.property/type ?type]
                                               (not [?e :logseq.property/built-in? _])]
                                             nil)
        matches (fn [names rows] (filter (fn [[t i]] (and (names t) (user-ident? i))) rows))]
    {:classes (->> (matches class-names user-classes)
                   (map (fn [[t i u]] {:name t :ident i :uuid u}))
                   (sort-by :name) vec)
     :properties (->> (matches prop-names user-props)
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

(defn- index-property-value
  "Value for an index property from the original value(s). Uses a set for :many
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

(defn- associated-entities
  "Entities tagged with class `ident`, or with a value for property `ident` when
  `type` is given"
  [graph ident type]
  (map first (logseq-util/logseq-query graph
                                       [:find (list 'pull '?e (entity-pull (when type ident) type))
                                        :where (if type ['?e ident '_] ['?e :block/tags ident])]
                                       nil)))

(defn- collect-migrations
  "Queries all entities associated with the conflicting classes and properties and
  returns `{:entities {id ops} :class-counts {name n} :prop-counts {name n}}` where
  ops is `{:uuid :page? :block :add-tags :remove-tags :add-props :remove-props}`
  aggregating every change for that entity. Values are read now, before any mutation."
  [graph {:keys [index class-ident property-ident]} {:keys [classes properties]}]
  (let [entities (atom {})
        counts (atom {:class-counts {} :prop-counts {}})
        update-entity (fn [e f]
                        (let [page? (nil? (:block/page e))]
                          (swap! entities update (:db/id e)
                                 #(-> (or % {:uuid (:block/uuid e) :page? page?
                                             :add-tags [] :remove-tags [] :add-props {} :remove-props []})
                                      (cond-> (not page?) (assoc :block (entity-block-info e)))
                                      f))))]
    (doseq [{:keys [name ident]} classes
            :let [ents (associated-entities graph ident nil)]]
      (swap! counts assoc-in [:class-counts name] (count ents))
      (doseq [e ents]
        (update-entity e #(-> %
                              (update :add-tags conj (class-ident (keyword name)))
                              (update :remove-tags conj ident)))))
    (doseq [{:keys [name ident type]} properties
            :let [ents (associated-entities graph ident type)]]
      (swap! counts assoc-in [:prop-counts name] (count ents))
      (doseq [e ents
              :let [v (index-property-value index name type (get e ident))]]
        (update-entity e #(-> %
                              (update :add-props assoc (property-ident (keyword name)) v)
                              (update :remove-props conj ident)))))
    (assoc @counts :entities @entities)))

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
  replacement can take the original name. An empty :build/properties is
  required: for an existing page the importer takes a \"minimal update\" shortcut
  that emits only the uuid and timestamps (dropping the new title) UNLESS the page
  carries :build/properties or :build/tags. The empty map forces the full path
  without writing any property. (:build/keep-uuid? does NOT work here - it routes
  to the create-new-page path, which does not rename the existing entity.)"
  [rename-prefix {:keys [classes properties]}]
  {:pages-and-blocks
   (vec (for [{:keys [name uuid]} (concat classes properties)]
          {:page {:block/uuid uuid
                  :block/title (str rename-prefix name)
                  :build/properties {}}}))})

(defn- property-declaration
  "Minimal declaration for an index property so the import's undeclared-property
  check passes; type and cardinality match the graph's existing property."
  [index name]
  (let [p (get-in index [:properties (keyword name)])]
    {:block/title name
     :logseq.property/type (:logseq.property/type p)
     :db/cardinality (if (= :many (:db/cardinality p)) :db.cardinality/many :db.cardinality/one)}))

(defn- extending-classes
  "User classes - other than the conflicts themselves - that extend a conflict
  class, as `{uuid #{class-idents}}` of the index classes they should now
  extend. The migration adds these so the relationship isn't lost when the
  originals (and their extends) are removed."
  [graph {:keys [class-ident]} {:keys [classes]}]
  (let [conflict-idents (set (map :ident classes))
        ident->replacement (into {} (map (fn [{:keys [ident name]}] [ident (class-ident (keyword name))])) classes)
        rows (when (seq conflict-idents)
               (logseq-util/logseq-query graph
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
                         [child-uuid (set (map (fn [[_ _ pident]] (ident->replacement pident)) child-rows))]))))))

(defn- conflict-aliases
  "Map of replacement :db/ident -> #{alias-target uuids}, read from the original
  conflict class/property pages so their :block/alias can be moved to the
  replacements before the originals are removed."
  [graph {:keys [class-ident property-ident]} {:keys [classes properties]}]
  (let [ident->replacement (into {} (concat
                                     (map (fn [{:keys [ident name]}] [ident (class-ident (keyword name))]) classes)
                                     (map (fn [{:keys [ident name]}] [ident (property-ident (keyword name))]) properties)))
        rows (when (seq ident->replacement)
               (logseq-util/logseq-query graph
                                         '[:find ?ident ?alias-uuid
                                           :in $ [?ident ...]
                                           :where
                                           [?e :db/ident ?ident]
                                           [?e :block/alias ?a]
                                           [?a :block/uuid ?alias-uuid]]
                                         [(vec (keys ident->replacement))]))]
    (reduce (fn [m [ident auuid]] (update m (ident->replacement ident) (fnil conj #{}) auuid)) {} rows)))

(defn- migrate-add-edn
  "Builds one import-edn map that adds tags and properties and copies property values to entities
   associated with changing properties and tags. Also handles aliases for onto entities
   and extends for classes"
  [{:keys [index property-ident]} {:keys [properties]} entities extends-by-uuid aliases]
  (let [prop-decls (into {} (map (fn [{:keys [name]}]
                                   [(property-ident (keyword name)) (property-declaration index name)]))
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
        ;; move each original's :block/alias onto its replacement (empty
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
                       (logseq-util/graph-args graph)
                       ["--id" (str id)]
                       (when (seq remove-tags) ["--remove-tags" (pr-str (vec remove-tags))])
                       (when (seq remove-props) ["--remove-properties" (pr-str (vec remove-props))]))
          {:keys [exit out err]} (apply shell {:continue true :out :string :err :string} args)]
      (when-not (zero? exit)
        (cli-util/error "Failed to remove user associations from entity" id "-" (str/trim (str out err)))))))

(defn- association-count
  "Number of entities still associated with an original class (by tag) or property"
  [graph {:keys [ident type]}]
  (or (logseq-util/logseq-query graph
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
        (map first (logseq-util/logseq-query graph
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
                                   (concat ["logseq" "remove" kind] (logseq-util/graph-args graph)
                                           ["--id" (str (ids ident))]))]
                        (when-not (zero? exit)
                          (cli-util/error "Failed to remove original" name "-" (str/trim (str out err))))))]
    (doseq [p properties] (remove-page "property" p))
    (doseq [c classes] (remove-page "tag" c))))

(defn- relationships
  "Extends and aliases to move onto the replacements, read from the originals
  before they're renamed"
  [graph config conflicts]
  {:extends-by-uuid (extending-classes graph config conflicts)
   :replacement-ident->aliases (conflict-aliases graph config conflicts)})

(defn- copy-edn
  "Step 4's import EDN. Aliases move onto each replacement by uuid, or by its
  ident when the replacement isn't in the graph yet e.g. for --pretend"
  [graph config conflicts entities {:keys [extends-by-uuid replacement-ident->aliases]}]
  (let [ident->uuid (logseq-util/idents->uuids graph (keys replacement-ident->aliases))
        aliases (into {} (map (fn [[ident targets]] [(get ident->uuid ident ident) targets]))
                      replacement-ident->aliases)]
    (migrate-add-edn config conflicts entities extends-by-uuid aliases)))

(defn- import-edn
  "Imports `edn` into `graph`, pretty printing it first when `verbose`"
  [graph edn verbose]
  (when verbose (pprint/pprint edn))
  (logseq-util/import-edn graph edn))

(defn- add-replacements
  "Steps 2-4: renames the originals, imports their replacements under the
  original names, then copies tags, values, extends and aliases onto them.
  Relationships are captured before the originals are renamed"
  [graph {:keys [add-schema rename-prefix] :as config} {:keys [properties classes] :as conflicts} entities verbose]
  (let [rels (relationships graph config conflicts)]
    (import-edn graph (rename-originals-edn rename-prefix conflicts) verbose)
    (add-schema (mapv :name (concat classes properties)) {:graph graph :pretend false})
    (import-edn graph (copy-edn graph config conflicts entities rels) verbose)))

(defn- print-replacement-edns
  "Pretty prints the EDN that [[add-replacements]] would import for steps 2
  and 4, without importing it"
  [graph config conflicts entities]
  (pprint/pprint (rename-originals-edn (:rename-prefix config) conflicts))
  (pprint/pprint (copy-edn graph config conflicts entities (relationships graph config conflicts))))

(defn- delete-originals
  "Steps 5-6: removes the original user tags and property values from the
  entities, confirms nothing is still associated, then removes the originals"
  [graph {:keys [properties classes] :as conflicts} entities]
  (remove-user-associations graph entities)
  (let [remaining (->> (concat properties classes)
                       (map (fn [c] [(:name c) (association-count graph c)]))
                       (filter (comp pos? second)))]
    (when (seq remaining)
      (cli-util/error "Migration incomplete; entities still associated with:"
                      (pr-str (into {} remaining))))
    (remove-originals graph conflicts)))

(defn- migrate* [graph config {:keys [properties classes] :as conflicts} entities {:keys [delete-existing verbose]}]
  (println "Migrating" (count properties) (pluralize properties "property" "properties")
           "and" (count classes) (pluralize classes "class" "classes")
           "across" (count entities) (pluralize entities "entity" "entities") "...")
  (println "Properties:" (and-join (sort (map :name properties))))
  (println "Classes:" (and-join (sort (map :name classes))))
  (add-replacements graph config conflicts entities verbose)
  (if delete-existing
    (delete-originals graph conflicts entities)
    (println "Kept the originals, renamed with prefix" (pr-str (:rename-prefix config))
             "- use --delete-existing to remove them."))
  (println "Migration complete."))

(def options
  "babashka.cli specs for the migrate-specific options of a migrate command"
  {:delete-existing {:alias :d :coerce :boolean
                     :desc "Remove the originals and their tags and property values after migrating"}
   :verbose {:alias :v :coerce :boolean
             :desc "Pretty print the EDN imported to rename originals and copy onto replacements"}})

(defn migrate
  "Migrates the user classes and properties in `:graph` that conflict with
  `config`'s index (see ns doc), or previews them when `:pretend` is set.
  The originals are only removed when `:delete-existing` is set. `:verbose`
  pretty prints the EDN imported, or that would be imported with `:pretend`"
  [{:keys [graph pretend verbose] :as opts} config]
  (let [conflicts (conflicts graph config)
        {:keys [classes properties]} conflicts
        ;; Step 1
        {:keys [entities class-counts prop-counts]} (collect-migrations graph config conflicts)]
    (cond
      (and (empty? classes) (empty? properties))
      (println "No user classes or properties found to migrate.")

      pretend
      (do (when verbose (print-replacement-edns graph config conflicts entities))
          (print-migrate-pretend conflicts entities class-counts prop-counts))

      :else
      (migrate* graph config conflicts entities opts))))
