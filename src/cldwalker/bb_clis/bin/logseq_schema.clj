(ns cldwalker.bb-clis.bin.logseq-schema
  "Manage schema.org classes and properties in a Logseq graph. Reads
  sqlite.build EDN from the classpath resource `schema-org-index.edn` and
  builds graph-ontology EDN that `logseq graph import --type edn` accepts."
  (:require [babashka.cli :as cli]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.logseq :as logseq-util]
            [cldwalker.bb-clis.util.logseq-migrate :as logseq-migrate]
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
  (let [dup-class-title? (logseq-util/existing-user-class-titles graph (map name check-classes))
        dup-prop-title? (logseq-util/existing-property-titles graph (map name check-props))
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
        existing (logseq-util/existing-idents graph
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
    (println "Adding" (count new-classes) (logseq-migrate/pluralize new-classes "class" "classes") "and"
             (count new-props) (logseq-migrate/pluralize new-props "property" "properties") "-"
             (into (vec new-classes) new-props))
    (if pretend
      (pprint/pprint edn)
      (logseq-util/import-edn graph edn))))

(defn- add-command [{{:keys [args] :as opts} :opts}]
  (when (empty? args)
    (cli-util/error "Usage: logseq-schema add [& PROPERTY-OR-CLASSES]"))
  (add-schema args opts))

;; ---- migrate ----

(defn- migrate [opts]
  (logseq-migrate/migrate opts {:index (read-index)
                                :class-ident class-ident
                                :property-ident property-ident
                                :add-schema add-schema
                                :rename-prefix "logseq-schema."}))

(defn- migrate-command [{:keys [opts]}]
  (migrate opts))

(defn- init-command [{:keys [opts]}]
  (add-schema ["Thing" "url"] opts))

(def ^:private add-spec
  {:graph {:alias :g
           :desc "Graph name"
           :complete-fn logseq-util/complete-graphs}
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
    :spec (merge add-spec logseq-migrate/options)
    :doc "Replace user classes and properties that conflict with schema.org names
  with their schema.org equivalents, moving tags and property values from the
  originals onto the replacements. The originals are kept, renamed, unless
  --delete-existing is set. Use --pretend to preview what would change."}])

(defn -main [& args]
  (cli/dispatch table args {:prog "logseq-schema" :help true}))
