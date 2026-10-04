(ns cldwalker.bb-clis.bin.logseq-create-graph-with-properties
  "Create a DB graph with all permutations of property types and cardinality,
   and a page of queries that exercises most properties.
   NOTE: Also used by Logseq's own CI to confirm graph creation works."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [borkdude.rewrite-edn :as rewrite]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.logseq :as logseq-util]
            [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

;; Copied from logseq.db.frontend.property.type
(def ^:private user-built-in-property-types
  "Valid property types for users in order they appear in the UI"
  [:default :number :date :datetime :checkbox :url :node :asset])

;; Copied from logseq.db.frontend.property.type
(def ^:private cardinality-property-types
  "Valid property types that can change cardinality"
  #{:default :number :url :date :node :asset})

(defn- ordinal-suffix
  [n]
  (if (<= 11 (mod n 100) 13)
    "th"
    (case (mod n 10) 1 "st" 2 "nd" 3 "rd" "th")))

(defn- date-journal-title
  "Java equivalent of (logseq.common.util.date-time/int->journal-title
   (logseq.common.util.date-time/date->int date) \"MMM do, yyyy\")"
  [^java.time.LocalDate date]
  (let [month (.format date (java.time.format.DateTimeFormatter/ofPattern "MMM" java.util.Locale/ENGLISH))
        day (.getDayOfMonth date)]
    (str month " " day (ordinal-suffix day) ", " (.getYear date))))

(defn- date->int
  "Java equivalent of logseq.common.util.date-time/date->int"
  [^java.time.LocalDate date]
  (Integer/parseInt (format "%04d%02d%02d" (.getYear date) (.getMonthValue date) (.getDayOfMonth date))))

(defn- ->page-ref
  "Java equivalent of logseq.common.util.page-ref/->page-ref"
  [page-name]
  (str "[[" page-name "]]"))

(defn- build-closed-values-config
  []
  {:default-closed
   (mapv #(hash-map :value %
                    :uuid (random-uuid)
                    :icon {:id % :type :emoji})
         ["joy" "sob" "upside_down_face"])
   :url-closed
   (mapv #(hash-map :value %
                    :uuid (random-uuid))
         ["https://logseq.com" "https://docs.logseq.com" "https://github.com/logseq/logseq"])
   :number-closed
   (mapv #(hash-map :value %
                    :uuid (random-uuid))
         [10 42 (rand 100)])})

(defn- build-ctx
  "Builds the shared, randomly-generated context that pages-and-blocks are built from"
  []
  (let [today (java.time.LocalDate/now)
        yesterday (.minusDays today 1)
        two-days-ago (.minusDays today 2)]
    {:today today
     :yesterday yesterday
     :today-int (date->int today)
     :yesterday-int (date->int yesterday)
     :two-days-ago-int (date->int two-days-ago)
     :closed-values-config (build-closed-values-config)
     :closed-values (atom {})
     :object-uuid (random-uuid)
     :timestamp (System/currentTimeMillis)}))

(defn- random-closed-value!
  [{:keys [closed-values-config closed-values]} k]
  (let [val (-> closed-values-config k rand-nth)]
    (swap! closed-values assoc k val)
    [:block/uuid (:uuid val)]))

(defn- closed-value [{:keys [closed-values]} k] (:value (get @closed-values k)))
(defn- closed-value-ref [{:keys [closed-values]} k] [:block/uuid (:uuid (get @closed-values k))])

(defn- query [query-string]
  {:block/title query-string
   :build/properties {:logseq.property/query query-string}
   :block/tags [{:db/ident :logseq.class/Query}]})

(defn- intro-pages
  [{:keys [object-uuid]}]
  [{:page {:block/title "Page 1"}}
   {:page {:block/title "Page object" :build/tags [:TestClass]}}
   {:page {:block/title "Blocks"}
    :blocks [{:block/title "block object"
              :block/uuid object-uuid
              ;; logseq graph import always sets :build-existing-tx? true so this
              ;; flag is needed to actually set the uuid
              :build/keep-uuid? true
              :build/tags [:TestClass]}]}])

(defn- journal-pages
  [{:keys [today-int yesterday-int two-days-ago-int]}]
  [{:page {:build/journal today-int}
    :blocks [{:block/title "[[Block Properties]]"}
             {:block/title "[[Property Queries]]"}
             {:block/title "[[Has Property Queries]]"}]}
   {:page {:build/journal yesterday-int}}
   {:page {:build/journal two-days-ago-int}}])

(defn- block-properties-page
  [{:keys [object-uuid today-int yesterday-int timestamp] :as ctx}]
  {:page {:block/title "Block Properties"
          :build/properties
          {:logseq.property/description "This page demonstrates all the combinations of property types and single/multiple values that are possible."}}
   :blocks
   [{:block/title "default property block" :build/properties {:default "haha"}}
    {:block/title "default property block" :build/properties {:default-many #{"yee" "haw" "sir"}}}
    {:block/title "default-closed property block" :build/properties {:default-closed (random-closed-value! ctx :default-closed)}}
    {:block/title "url property block" :build/properties {:url "https://logseq.com"}}
    {:block/title "url-many property block" :build/properties {:url-many #{"https://logseq.com" "https://docs.logseq.com"}}}
    {:block/title "url-closed property block" :build/properties {:url-closed (random-closed-value! ctx :url-closed)}}
    {:block/title "checkbox property block" :build/properties {:checkbox true}}
    {:block/title "number property block" :build/properties {:number 5}}
    {:block/title "number-many property block" :build/properties {:number-many #{5 10}}}
    {:block/title "number-closed property block" :build/properties {:number-closed (random-closed-value! ctx :number-closed)}}
    {:block/title "node property block" :build/properties {:node [:block/uuid object-uuid]}}
    {:block/title "node without classes property block" :build/properties {:node-without-classes [:build/page {:block/title "Page 1"}]}}
    {:block/title "node-many property block" :build/properties {:node-many #{[:block/uuid object-uuid] [:build/page {:block/title "Page object"}]}}}
    {:block/title "date property block" :build/properties {:date [:build/page {:build/journal today-int}]}}
    {:block/title "date-many property block" :build/properties {:date-many #{[:build/page {:build/journal today-int}]
                                                                             [:build/page {:build/journal yesterday-int}]}}}
    {:block/title "datetime property block" :build/properties {:datetime timestamp}}]})

(defn- property-queries-page
  [{:keys [today yesterday timestamp] :as ctx}]
  {:page {:block/title "Property Queries"
          :build/properties
          {:logseq.property/description "This page demonstrates all property type combinations being queried for a specific value. There should be 2 results for each query, one block and one page."}}
   :blocks
   [(query "(property default \"haha\")")
    (query "(property default-many \"haw\")")
    (query (str "(property default-closed " (pr-str (closed-value ctx :default-closed)) ")"))
    (query "(property url \"https://logseq.com\")")
    (query "(property url-many \"https://logseq.com\")")
    (query (str "(property url-closed " (pr-str (closed-value ctx :url-closed)) ")"))
    (query "(property checkbox true)")
    (query "(property number 5)")
    (query "(property number-many 10)")
    (query (str "(property number-closed " (pr-str (closed-value ctx :number-closed)) ")"))
    (query "(property node \"block object\")")
    (query "(property node-without-classes [[Page 1]])")
    (query "(property node-many [[Page object]])")
    (query (str "(property date " (->page-ref (str/capitalize (date-journal-title today))) ")"))
    (query (str "(property date-many " (->page-ref (str/capitalize (date-journal-title yesterday))) ")"))
    (query (str "(property datetime "  timestamp ")"))]})

(defn- page-property-pages
  [{:keys [object-uuid today-int yesterday-int timestamp] :as ctx}]
  [{:page {:block/title "default page" :build/properties {:default "haha"}}}
   {:page {:block/title "default-many page" :build/properties {:default-many #{"yee" "haw" "sir"}}}}
   {:page {:block/title "default-closed page" :build/properties {:default-closed (closed-value-ref ctx :default-closed)}}}
   {:page {:block/title "url page" :build/properties {:url "https://logseq.com"}}}
   {:page {:block/title "url-many page" :build/properties {:url-many #{"https://logseq.com" "https://docs.logseq.com"}}}}
   {:page {:block/title "url-closed page" :build/properties {:url-closed (closed-value-ref ctx :url-closed)}}}
   {:page {:block/title "checkbox page" :build/properties {:checkbox true}}}
   {:page {:block/title "number page" :build/properties {:number 5}}}
   {:page {:block/title "number-many page" :build/properties {:number-many #{5 10}}}}
   {:page {:block/title "number-closed page" :build/properties {:number-closed (closed-value-ref ctx :number-closed)}}}
   {:page {:block/title "node page" :build/properties {:node [:block/uuid object-uuid]}}}
   {:page {:block/title "node without classes page" :build/properties {:node-without-classes [:build/page {:block/title "Page 1"}]}}}
   {:page {:block/title "node-many page" :build/properties {:node-many #{[:block/uuid object-uuid] [:build/page {:block/title "Page object"}]}}}}
   {:page {:block/title "date page" :build/properties {:date [:build/page {:build/journal today-int}]}}}
   {:page {:block/title "date-many page" :build/properties {:date-many #{[:build/page {:build/journal today-int}]
                                                                         [:build/page {:build/journal yesterday-int}]}}}}
   {:page {:block/title "datetime page" :build/properties {:datetime timestamp}}}])

(defn- has-property-queries-page
  []
  {:page {:block/title "Has Property Queries"
          :build/properties
          {:logseq.property/description "This page demonstrates all property type combinations being queried for having a specific property. There should be 2 results for each query, one block and one page."}}
   :blocks
   [(query "(property default)")
    (query "(property default-many)")
    (query "(property default-closed)")
    (query "(property url)")
    (query "(property url-many)")
    (query "(property url-closed)")
    (query "(property checkbox)")
    (query "(property number)")
    (query "(property number-many)")
    (query "(property number-closed)")
    (query "(property node)")
    (query "(property node-without-classes)")
    (query "(property node-many)")
    (query "(property date)")
    (query "(property date-many)")
    (query "(property datetime)")]})

(defn- properties-config
  [ctx]
  (->> user-built-in-property-types
       (mapcat #(cond-> (if (= :node %)
                          [[% {:logseq.property/type % :build/property-classes [:TestClass]}]
                           [:node-without-classes {:logseq.property/type %}]]
                          [[% {:logseq.property/type %}]])
                  (contains? cardinality-property-types %)
                  (conj [(keyword (str (name %) "-many"))
                         (cond-> {:logseq.property/type %
                                  :db/cardinality :many}
                           (= :node %)
                           (assoc :build/property-classes [:TestClass]))])))
       (into (mapv #(vector (keyword (str (name %) "-closed"))
                            {:logseq.property/type %
                             :build/closed-values ((:closed-values-config ctx) (keyword (str (name %) "-closed")))})
                   [:default :url :number]))
       (into {})))

(defn- create-init-data
  []
  (let [ctx (build-ctx)]
    {:pages-and-blocks
     (vec
      ; intro-pages are page property values that must come first b/c they are referenced later
      (concat (intro-pages ctx)
              (journal-pages ctx)
              [(block-properties-page ctx)
               (property-queries-page ctx)]
              (page-property-pages ctx)
              [(has-property-queries-page)]))
     :classes {:TestClass {}}
     :properties (properties-config ctx)}))

(defn- spit-edn
  [path data]
  (binding [*print-namespace-maps* false]
    (spit path (with-out-str (pprint/pprint data)))))

(defn- import-graph!
  [graph edn-path]
  ;; $LOGSEQ_STABLE_IDENTS only way to prevent suffix crap on specified EDN idents
  (shell {:extra-env {"LOGSEQ_STABLE_IDENTS" "true"}}
         "logseq" "graph" "import" "--graph" graph "--type" "edn" "--input" (str edn-path)))

(defn- logseq-query
  [graph query]
  (let [{:keys [out]} (apply shell {:out :string :continue true} "logseq" "query"
                             (concat (when graph ["-g" graph])
                                     ["-o" "edn" "--query" (pr-str query)]))
        {:keys [status data error]} (edn/read-string out)]
    (when (not= :ok status)
      (cli-util/error "Query failed:" (or (:message error) (pr-str error) out)))
    (:result data)))

(defn- current-config-content
  [graph]
  (:file/content (logseq-query graph '[:find (pull ?b [:file/content]) . :where [?b :file/path "logseq/config.edn"]])))

(defn- merge-config-edn
  "Merges a config map into an existing config.edn's content while preserving
   its formatting. Java port of logseq.outliner.cli/pretty-print-merge"
  [content config-map]
  (-> (reduce (fn [acc [k v]] (rewrite/assoc acc k v))
              (rewrite/parse-string content)
              config-map)
      str))

(defn- config-init-data
  "Builds a standalone EDN payload that only upserts config.edn via the
   ::graph-files mechanism that logseq.db.sqlite.export/build-import
   understands. Importing this separately from the main init-data avoids a
   build-import limitation where combining ::graph-files with a large
   pages-and-blocks/properties payload in one import fails validation -
   see logseq.db.test.helper, which transacts ::graph-files separately
   for the same reason"
  [content config-map]
  {:pages-and-blocks []
   :classes {}
   :properties {}
   ;; weird this is needed
   :logseq.db.sqlite.export/export-type :graph
   :logseq.db.sqlite.export/graph-files
   [{:file/path "logseq/config.edn"
     :file/content (merge-config-edn content config-map)}]})

(defn- with-temp-edn-file!
  "Writes data to a temp EDN file, runs f on its path, then deletes it"
  [data f]
  (let [path (str (fs/create-temp-file {:suffix ".edn"}))]
    (spit-edn path data)
    (f path)
    (fs/delete-if-exists path)))

(defn- create-graph!
  [graph config-map]
  (with-temp-edn-file! (create-init-data) #(import-graph! graph %))
  (when config-map
    (let [content (current-config-content graph)]
      (with-temp-edn-file! (config-init-data content config-map) #(import-graph! graph %))))
  (println "Created graph" graph "with properties!"))

(defn- command
  [{:keys [opts]}]
  (let [{:keys [graph file config]} opts]
    (if file
      (do (spit-edn file (create-init-data))
          (println "Wrote sqlite.build EDN to" file))
      (create-graph! graph config))))

(def ^:private spec
  {:graph {:positional true :coerce :string :require true :desc "Graph to create"
           :complete-fn logseq-util/complete-graphs}
   :file {:alias :f :desc "Write generated sqlite.build EDN to this file and exit, skipping graph creation"}
   :config {:alias :c :coerce edn/read-string :desc "EDN map to merge into the created graph's config.edn"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec :args->opts [:graph] :restrict-args true}]
                args
                {:prog "logseq-create-graph-with-properties" :help true}))
