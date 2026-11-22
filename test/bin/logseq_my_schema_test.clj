(ns bin.logseq-my-schema-test
  (:require [babashka.fs :as fs]
            [bin.logseq-migrate-fake :as fake]
            [cldwalker.bb-clis.bin.logseq-my-schema :as my-schema]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.logseq :as logseq-util]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private index
  {:classes {:Child {:block/title "Child"
                     :build/properties {:url "https://tagaholic.me/schema/Child.html"}
                     :build/alias [:Kid]
                     :build/class-extends [:Parent]}
             :Parent {:block/title "Parent"
                      :build/properties {:url "https://tagaholic.me/schema/Parent.html"}
                      :build/class-extends [:schema.class/Thing]}
             :TextEditor {:block/title "TextEditor"
                          :build/properties {:url "https://tagaholic.me/schema/TextEditor.html"
                                             :schema.property/sameAs "https://en.wikipedia.org/wiki/Text_editor"}}}
   :properties {:forApp {:logseq.property/type :node
                         :db/cardinality :many
                         :build/properties {:url "https://tagaholic.me/schema/forApp.html"
                                            :meta "A note"}
                         :build/alias [:app]}
                :meta {:logseq.property/type :default
                       :build/properties {:url "https://tagaholic.me/schema/meta.html"}}}})

(def ^:private url-anchor
  {:block/title "url" :logseq.property/type :url :db/cardinality :db.cardinality/one})

(defn- alias-uuid
  "Returns the uuid of the alias page titled `title` in `edn`"
  [edn title]
  (some #(when (= title (get-in % [:page :block/title])) (get-in % [:page :block/uuid]))
        (:pages-and-blocks edn)))

(defn- kid-uuid [edn] (alias-uuid edn "Kid"))

(defn- child-edn
  "Returns the expected EDN for adding Child, aliased to a new Kid page
  with uuid `kid`"
  [kid]
  {:classes {:tagaholic.me.class/Child
             {:block/title "Child"
              :build/properties {:schema.property/url "https://tagaholic.me/schema/Child.html"}
              :build/class-extends #{:tagaholic.me.class/Parent}
              :block/alias #{[:block/uuid kid]}}}
   :properties {:schema.property/url url-anchor}
   :pages-and-blocks [{:page {:block/title "Kid" :block/uuid kid :build/keep-uuid? true}}]})

(defn- build-add-edn
  "Runs build-add-edn on `args` against a fake graph that already has
  schema.org's url and Thing plus the given index classes and properties and
  `graph-idents`"
  [args & {:keys [graph-classes graph-props graph-idents]}]
  (let [idents (into #{:schema.property/url :schema.class/Thing}
                     (concat graph-idents
                             (map #(keyword "tagaholic.me.class" (name %)) graph-classes)
                             (map #(keyword "tagaholic.me.property" (name %)) graph-props)))
        existing-titles (fn [ks] (fn [_graph titles] (set (filter (set (map name ks)) titles))))]
    (with-redefs [logseq-util/existing-idents (fn [_graph ks] (set (filter idents ks)))
                  logseq-util/existing-user-class-titles (existing-titles graph-classes)
                  logseq-util/existing-property-titles (existing-titles graph-props)
                  cli-util/error (fn [& msgs] (throw (ex-info (str/join " " msgs) {})))]
      (#'my-schema/build-add-edn index "graph" args #{}))))

(deftest build-properties
  (testing "a {{}} url is only this graph's schema url"
    (is (= {:url "https://tagaholic.me/schema/TextEditor.html"}
           (#'my-schema/build-properties "TextEditor" {"url" "{{schema-url}}"}))))
  (testing "an external url becomes sameAs alongside this graph's schema url"
    (is (= {:url "https://tagaholic.me/schema/TextEditor.html"
            :schema.property/sameAs "https://en.wikipedia.org/wiki/Text_editor"}
           (#'my-schema/build-properties "TextEditor" {"url" "https://en.wikipedia.org/wiki/Text_editor"})))))

(deftest build-add-edn-new-class
  (testing "adds the class, aliased to new pages, and its local ancestors with qualified idents"
    (let [{:keys [edn] :as result} (build-add-edn [:Child])]
      (is (some? (kid-uuid edn)))
      (is (= {:new-classes #{:Child :Parent}
              :edn (assoc-in (child-edn (kid-uuid edn))
                             [:classes :tagaholic.me.class/Parent]
                             {:block/title "Parent"
                              :build/properties {:schema.property/url "https://tagaholic.me/schema/Parent.html"}
                              :build/class-extends #{:schema.class/Thing}})}
             (-> result
                 (update :new-classes set)
                 (dissoc :new-props)))))))

(deftest build-add-edn-new-property
  (testing "adds the property, aliased to new pages, and the meta property it uses"
    (let [{:keys [edn] :as result} (build-add-edn [:forApp])
          app (alias-uuid edn "app")]
      (is (some? app))
      (is (= {:new-classes []
              :new-props [:forApp :meta]
              :edn {:properties {:tagaholic.me.property/forApp
                                 {:block/title "forApp"
                                  :logseq.property/type :node
                                  :db/cardinality :db.cardinality/many
                                  :build/properties {:schema.property/url "https://tagaholic.me/schema/forApp.html"
                                                     :tagaholic.me.property/meta "A note"}
                                  :block/alias #{[:block/uuid app]}}
                                 :tagaholic.me.property/meta
                                 {:block/title "meta"
                                  :logseq.property/type :default
                                  :db/cardinality :db.cardinality/one
                                  :build/properties {:schema.property/url "https://tagaholic.me/schema/meta.html"}}
                                 :schema.property/url url-anchor}
                    :pages-and-blocks [{:page {:block/title "app" :block/uuid app :build/keep-uuid? true}}]}}
             result)))))

(deftest build-add-edn-existing-class
  (testing "skips an ancestor already in the graph"
    (let [{:keys [edn] :as result} (build-add-edn [:Child] :graph-classes [:Parent])]
      (is (= {:new-classes [:Child] :new-props [] :edn (child-edn (kid-uuid edn))}
             result))))
  (testing "errors when the class arg itself is in the graph"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exist: 'Child'"
                          (build-add-edn [:Child] :graph-classes [:Child :Parent])))))

(deftest build-add-edn-existing-property
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exist: 'forApp'"
                        (build-add-edn [:forApp] :graph-props [:forApp]))))

(deftest build-add-edn-same-as
  (testing "declares schema.org's sameAs when a new entity uses it"
    (is (= {:schema.property/url url-anchor
            :schema.property/sameAs {:block/title "sameAs" :logseq.property/type :url :db/cardinality :db.cardinality/one}}
           (-> (build-add-edn [:TextEditor] :graph-idents [:schema.property/sameAs])
               (get-in [:edn :properties])))))
  (testing "errors when sameAs isn't in the graph"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sameAs not found"
                          (build-add-edn [:TextEditor])))))

(def ^:private update-entries
  {:SoftwareSourceCode {:build/alias [:Repository :Repo]}
   :Service {:build/properties {:tagaholic.me/meta "New note"}}
   :Text {:build/alias [:String] :build/properties {:tagaholic.me/meta "Same note"}}
   :Brand {:build/alias [:Make]}})

(def ^:private code-uuid #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private service-uuid #uuid "00000000-0000-0000-0000-000000000002")

(defn- build-update-edn
  "Runs build-update-edn on [[update-entries]] against a graph without Brand
  whose other classes have some of their aliases and meta"
  [& {:keys [meta-exists?]}]
  (#'my-schema/build-update-edn
   index
   (for [[k entry] (sort-by key update-entries)]
     (#'my-schema/update-target k (keyword "schema.class" (name k)) entry #'my-schema/schema-org-property-key->ident))
   {:uuids (cond-> {:schema.class/SoftwareSourceCode code-uuid
                    :schema.class/Service service-uuid
                    :schema.class/Text #uuid "00000000-0000-0000-0000-000000000003"}
             meta-exists? (assoc :tagaholic.me.property/meta #uuid "00000000-0000-0000-0000-000000000004"))
    :aliases {:schema.class/SoftwareSourceCode #{"repo"} :schema.class/Text #{"String"}}
    :values {:schema.class/Service {:tagaholic.me.property/meta "Old note"}
             :schema.class/Text {:tagaholic.me.property/meta "Same note"}}}))

(deftest build-update-edn-aliases-and-meta
  (let [{:keys [edn] :as result} (build-update-edn :meta-exists? true)
        repository-uuid (some #(when (= "Repository" (get-in % [:page :block/title])) (get-in % [:page :block/uuid]))
                              (:pages-and-blocks edn))]
    (testing "only entities whose aliases or meta differ are updated"
      (is (= {:updated [:Service :SoftwareSourceCode] :current [:Text] :missing [:Brand] :new-props []}
             (dissoc result :edn))))
    (testing "adds only missing aliases, case-insensitively, and changed meta"
      (is (= {:pages-and-blocks
              [{:page {:block/uuid service-uuid
                       :build/properties {:tagaholic.me.property/meta "New note"}}}
               {:page {:block/title "Repository" :block/uuid repository-uuid :build/keep-uuid? true}}
               {:page {:block/uuid code-uuid
                       :build/properties {}
                       :block/alias #{[:block/uuid repository-uuid]}}}]
              :properties {:tagaholic.me.property/meta
                           {:block/title "meta" :logseq.property/type :default :db/cardinality :db.cardinality/one}}}
             edn)))))

(deftest build-update-edn-meta-not-in-graph
  (let [{:keys [new-props edn]} (build-update-edn)]
    (is (= [:meta] new-props))
    (is (= {:tagaholic.me.property/meta
            {:block/title "meta"
             :logseq.property/type :default
             :db/cardinality :db.cardinality/one
             :build/properties {:schema.property/url "https://tagaholic.me/schema/meta.html"}}
            :schema.property/url url-anchor}
           (:properties edn)))))

(deftest build-update-edn-ui-position
  (let [about-uuid #uuid "00000000-0000-0000-0000-000000000007"
        build (fn [attributes]
                (#'my-schema/build-update-edn
                 index
                 [(#'my-schema/update-target :about :schema.property/about
                                             {:logseq.property/ui-position :block-right}
                                             #'my-schema/schema-org-property-key->ident)]
                 {:uuids {:schema.property/about about-uuid}
                  :attributes {:schema.property/about attributes}}))]
    (testing "updates a changed ui-position through the property page's :build/properties"
      (is (= {:updated [:about] :current [] :missing [] :new-props []
              :edn {:pages-and-blocks [{:page {:block/uuid about-uuid
                                               :build/properties {:logseq.property/ui-position :block-right}}}]}}
             (build {:logseq.property/ui-position :block-left}))))
    (testing "leaves a matching ui-position as is"
      (is (= [:about] (:current (build {:logseq.property/ui-position :block-right})))))))

(def ^:private text-editor-uuid #uuid "00000000-0000-0000-0000-000000000005")

(defn- build-my-update-edn
  "Runs build-update-edn on [[index]]'s TextEditor against a graph whose
  TextEditor still has its former url as its url and no sameAs"
  [& {:keys [same-as-exists?]}]
  (#'my-schema/build-update-edn
   index
   (#'my-schema/my-update-targets index [:TextEditor])
   {:uuids (cond-> {:tagaholic.me.class/TextEditor text-editor-uuid}
             same-as-exists? (assoc :schema.property/sameAs #uuid "00000000-0000-0000-0000-000000000006"))
    :values {:tagaholic.me.class/TextEditor {:schema.property/url "https://en.wikipedia.org/wiki/Text_editor"}}}))

(deftest build-update-edn-url-and-same-as
  (testing "updates changed url and sameAs values, declaring both properties"
    (is (= {:updated [:TextEditor] :current [] :missing [] :new-props []
            :edn {:pages-and-blocks
                  [{:page {:block/uuid text-editor-uuid
                           :build/properties {:schema.property/url "https://tagaholic.me/schema/TextEditor.html"
                                              :schema.property/sameAs "https://en.wikipedia.org/wiki/Text_editor"}}}]
                  :properties {:schema.property/url url-anchor
                               :schema.property/sameAs {:block/title "sameAs" :logseq.property/type :url :db/cardinality :db.cardinality/one}}}}
           (build-my-update-edn :same-as-exists? true))))
  (testing "errors when sameAs isn't in the graph"
    (with-redefs [cli-util/error (fn [& msgs] (throw (ex-info (str/join " " msgs) {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"sameAs not found"
                            (build-my-update-edn))))))

(deftest my-update-targets
  (testing "targets every class and then property, with tagaholic.me idents and qualified properties"
    (is (= [[:Child :tagaholic.me.class/Child [:Kid] {:schema.property/url "https://tagaholic.me/schema/Child.html"}]
            [:Parent :tagaholic.me.class/Parent nil {:schema.property/url "https://tagaholic.me/schema/Parent.html"}]
            [:TextEditor :tagaholic.me.class/TextEditor nil {:schema.property/url "https://tagaholic.me/schema/TextEditor.html"
                                                             :schema.property/sameAs "https://en.wikipedia.org/wiki/Text_editor"}]
            [:forApp :tagaholic.me.property/forApp [:app] {:schema.property/url "https://tagaholic.me/schema/forApp.html"
                                                           :tagaholic.me.property/meta "A note"}]
            [:meta :tagaholic.me.property/meta nil {:schema.property/url "https://tagaholic.me/schema/meta.html"}]]
           (map (juxt :k :ident :aliases :properties) (#'my-schema/my-update-targets index [])))))
  (testing "targets only the named classes and properties"
    (is (= [:Child :forApp]
           (map :k (#'my-schema/my-update-targets index [:forApp :Child]))))))

(deftest graph-dirs
  (fs/with-temp-dir [dir {}]
    (doseq [d ["a/pages" "b/pages" "not-a-graph"]] (fs/create-dirs (fs/path dir d)))
    (testing "a directory of graphs returns each graph"
      (is (= ["a" "b"] (map (comp str fs/file-name) (#'my-schema/graph-dirs dir)))))
    (testing "a graph returns itself"
      (is (= [(fs/path dir "a")] (#'my-schema/graph-dirs (fs/path dir "a")))))))

(deftest build-index
  (fs/with-temp-dir [dir {}]
    (let [pages (fs/path dir "pages")]
      (fs/create-dirs pages)
      (spit (fs/file pages "Gadget.md")
            "type:: [[Class]]\nurl:: https://example.com/gadget\nparent:: [[Thing]]\ndesc:: A gadget\n\n- A block\n\t- nested")
      (spit (fs/file pages "gizmo.md") "type:: [[Property]]\nurl:: {{x}}\nrangeIncludes:: [[Text]]\n")
      (spit (fs/file pages "owner.md") "type:: [[Property]]\nurl:: {{x}}\nrangeIncludes:: [[Person]]\n")
      (spit (fs/file pages "Human.md") "type:: [[Class]]\nurl:: https://schema.org/Person\nmeta:: A note")
      (spit (fs/file pages "Note.md") "type:: [[Gadget]]\n- just a block")
      (is (= {:tagaholic {:graph-namespace :tagaholic.me
                          :classes {:Gadget {:block/title "Gadget"
                                             :build/properties {:url "https://tagaholic.me/schema/Gadget.html"
                                                                :schema.property/sameAs "https://example.com/gadget"
                                                                :logseq.property/description "A gadget"}
                                             :build/class-extends [:schema.class/Thing]}}
                          :properties {:gizmo {:block/title "gizmo"
                                               :logseq.property/type :default
                                               :build/properties {:url "https://tagaholic.me/schema/gizmo.html"}}
                                       :owner {:block/title "owner"
                                               :logseq.property/type :node
                                               :build/property-classes [:schema.class/Person]
                                               :build/properties {:url "https://tagaholic.me/schema/owner.html"}}}}
              :my-schema-org {:graph-namespace :schema
                              :classes {:Person {:build/alias [:Human]
                                                 :build/properties {:tagaholic.me/meta "A note"}}}
                              :properties {}}}
             (#'my-schema/build-index dir))))))

(deftest build-index-command-without-graphs
  (fs/with-temp-dir [dir {}]
    (with-redefs [cli-util/error (fn [& msgs] (throw (ex-info (str/join " " msgs) {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No graph directories found in"
                            (#'my-schema/build-index-command {:opts {:directory (str dir)}}))))))

(deftest resolve-page
  (fs/with-temp-dir [dir {}]
    (fs/create-dirs (fs/path dir "g/pages"))
    (spit (fs/file dir "g/pages/Widget.md")
          (str "type:: [[ProgrammingLanguage]], [[Kid]]\nalias:: wd\ndesc:: A thing\nforApp:: [[Emacs]], [[Vim]]\n"
               "birthDate:: 2020-01-02\ncreated-at:: 123\n\n- block one\n  - nested\n-\n"))
    (spit (fs/file dir "g/pages/Bad.md") "type:: Nope\nbogus:: x\nbirthDate:: someday")
    (let [resolve (fn [page] (#'my-schema/resolve-page (#'my-schema/import-lookups index) index
                                                       (#'my-schema/find-page [(fs/path dir "g")] page)))]
      (testing "resolves tags and properties by name or alias, converting values by property type"
        (is (= {:title "Widget"
                :blocks 2
                :tags [[:schema :ComputerLanguage] [:tagaholic :Child]]
                :properties {[:logseq :description] "A thing"
                             [:tagaholic :forApp] #{[:build/page {:block/title "Emacs"}] [:build/page {:block/title "Vim"}]}
                             [:schema :birthDate] [:build/page {:build/journal 20200102}]}
                :alias ["wd"]
                :errors []}
               (resolve "widget"))))
      (testing "reports unknown tags and properties and unconvertible values"
        (is (= ["Unknown tag Nope" "Unknown property bogus" "Unable to convert birthDate:: someday to a date value"]
               (:errors (resolve "Bad"))))))))

;; ---- import acceptance tests ----

(def ^:private widget-page "type:: [[Kid]], [[Person]]\nforApp:: [[Emacs]]\ndesc:: A widget\n")

(def ^:private widget-graph-idents
  "Idents for a graph that already has every tag and property [[widget-page]] uses"
  #{:schema.property/url :schema.class/Thing :schema.class/Person
    :tagaholic.me.class/Child :tagaholic.me.class/Parent
    :tagaholic.me.property/forApp :tagaholic.me.property/meta})

(defn- run-import
  "Runs `logseq-my-schema import -g g -d DIR & args` through `-main`, where DIR
  holds `graph->pages`, a map of graph name to `{page-name content}`. Runs
  against [[index]] and a fake Logseq graph with `existing` idents, returning
  [[bin.logseq-migrate-fake/run]]'s result"
  [graph->pages existing & args]
  (fs/with-temp-dir [dir {}]
    (doseq [[graph pages] graph->pages
            [page content] pages]
      (fs/create-dirs (fs/path dir graph "pages"))
      (spit (fs/file dir graph "pages" (str page ".md")) content))
    (with-redefs-fn {#'my-schema/read-my-index (constantly index)
                     #'my-schema/read-my-schema-org-index (constantly {:classes {} :properties {}})}
      #(fake/run {:existing existing}
                 (fn [] (apply my-schema/-main "import" "-g" "g" "-d" (str dir) args))))))

(defn- page-entry [edn title]
  (some #(when (= title (get-in % [:page :block/title])) (:page %)) (:pages-and-blocks edn)))

(deftest import-adds-missing-tags-and-properties
  (let [{:keys [out error imports]}
        (run-import {"g1" {"Widget" (str widget-page "alias:: wd\n\n- a block\n  - nested\n")}}
                    #{:schema.property/url}
                    "widget")
        [edn] imports
        wd (alias-uuid edn "wd")]
    (is (nil? error))
    (testing "adds new tags with their ancestors, including schema.org ones, and new properties"
      (is (str/includes? out "Importing 1 page and adding 4 classes and 2 properties"))
      (is (= #{:schema.class/Person :schema.class/Thing :tagaholic.me.class/Child :tagaholic.me.class/Parent}
             (set (keys (:classes edn)))))
      (is (= #{:schema.property/url :tagaholic.me.property/forApp :tagaholic.me.property/meta}
             (set (keys (:properties edn))))))
    (testing "imports the page with its tags, properties and aliases but no blocks"
      (is (some? wd))
      (is (= {:block/title "Widget"
              :build/tags #{:tagaholic.me.class/Child :schema.class/Person}
              :build/properties {:tagaholic.me.property/forApp #{[:build/page {:block/title "Emacs"}]}
                                 :logseq.property/description "A widget"}
              :block/alias #{[:block/uuid wd]}}
             (page-entry edn "Widget")))
      (is (str/includes? out "Imported page 'Widget', skipping its 2 blocks\n")))))

(deftest import-adds-schema-org-parents-of-new-tags
  (testing "a new tagaholic.me tag's missing schema.org ancestors are added with it"
    (let [{:keys [error imports]} (run-import {"g1" {"Kidpage" "type:: [[Kid]]"}} #{:schema.property/url} "Kidpage")]
      (is (nil? error))
      (is (= #{:schema.class/Thing :tagaholic.me.class/Child :tagaholic.me.class/Parent}
             (set (keys (:classes (first imports)))))))))

(deftest import-reuses-existing-tags-and-properties
  (let [{:keys [out error imports]} (run-import {"g1" {"Widget" widget-page}} widget-graph-idents "Widget")]
    (is (nil? error))
    (is (str/includes? out "Importing 1 page and adding 0 classes and 0 properties - []"))
    (is (str/includes? out "Imported page 'Widget'\n"))
    (testing "only declares the existing properties the page sets"
      (is (= [{:pages-and-blocks [{:page {:block/title "Widget"
                                          :build/tags #{:tagaholic.me.class/Child :schema.class/Person}
                                          :build/properties {:tagaholic.me.property/forApp #{[:build/page {:block/title "Emacs"}]}
                                                             :logseq.property/description "A widget"}}}]
               :properties {:tagaholic.me.property/forApp
                            {:block/title "forApp" :logseq.property/type :node :db/cardinality :db.cardinality/many}}}]
             imports)))))

(deftest import-pretend
  (let [graphs {"g1" {"Widget" widget-page}}
        {:keys [out imports]} (run-import graphs widget-graph-idents "-n" "Widget")]
    (testing "prints the EDN that would be imported instead of importing it"
      (is (= [] imports))
      (is (= (:imports (run-import graphs widget-graph-idents "Widget"))
             [(edn/read-string (subs out (str/index-of out "{")))]))
      (is (not (str/includes? out "Imported page"))))))

(deftest import-searches-every-graph
  (testing "finds a page in any graph"
    (is (= ["Widget"]
           (map #(get-in % [:pages-and-blocks 0 :page :block/title])
                (:imports (run-import {"g1" {"Other" "type:: [[Person]]"} "g2" {"Widget" widget-page}}
                                      widget-graph-idents "Widget"))))))
  (testing "a page in several graphs with the same properties imports from the first"
    (is (str/includes? (:out (run-import {"g1" {"Widget" (str widget-page "- one")}
                                          "g2" {"Widget" (str widget-page "- one\n- two")}}
                                         widget-graph-idents "Widget"))
                       "Imported page 'Widget', skipping its 1 block\n")))
  (testing "errors when graphs have a page with different properties"
    (is (= "Page \"Widget\" has different properties in graphs: g1, g2"
           (:error (run-import {"g1" {"Widget" widget-page} "g2" {"Widget" "type:: [[Person]]"}}
                               widget-graph-idents "Widget"))))))

(deftest import-errors
  (let [graphs {"g1" {"Widget" widget-page
                      "Bad" "type:: [[Nope]]\nbogus:: x"
                      "Gadget" "type:: [[Class]]\nurl:: https://example.com"}}]
    (testing "requires pages"
      (is (= "Usage: logseq-my-schema import [& PAGES]" (:error (run-import graphs widget-graph-idents)))))
    (testing "errors on a page no graph has"
      (is (= "Page \"Nope\" not found in graphs: g1" (:error (run-import graphs widget-graph-idents "Widget" "Nope")))))
    (testing "lists every page's unknown tags and properties and ontology pages, importing nothing"
      (let [{:keys [error imports]} (run-import graphs widget-graph-idents "Widget" "Bad" "Gadget")]
        (is (= [] imports))
        (is (= (str "Unable to import these pages:\n"
                    "  Bad: Unknown tag Nope\n"
                    "  Bad: Unknown property bogus\n"
                    "  Gadget: Is a Class page. Use add for it instead")
               error))))))

(defn- graph-result [tagaholic-classes my-schema-org-props]
  {:tagaholic {:graph-namespace :tagaholic.me :classes tagaholic-classes :properties {}}
   :my-schema-org {:graph-namespace :schema :classes {} :properties my-schema-org-props}})

(deftest merge-graph-indexes
  (with-redefs [cli-util/error (fn [& msgs] (throw (ex-info (str/join " " msgs) {})))]
    (testing "merges each index's entries, allowing identical shared ones"
      (is (= {:tagaholic {:graph-namespace :tagaholic.me :classes {:A {:block/title "A"} :B {:block/title "B"}} :properties {}}
              :my-schema-org {:graph-namespace :schema :classes {} :properties {:p {:build/alias [:q]}}}}
             (#'my-schema/merge-graph-indexes
              nil
              [["g1" (graph-result {:A {:block/title "A"}} {:p {:build/alias [:q]}})]
               ["g2" (graph-result {:A {:block/title "A"} :B {:block/title "B"}} {})]]))))
    (testing "errors listing every differing entry in either index"
      (let [msg (try (#'my-schema/merge-graph-indexes
                      nil
                      [["g1" (graph-result {:A {:block/title "A"} :B {}} {:p {:build/alias [:q]}})]
                       ["g2" (graph-result {:A {:block/title "A2"} :B {}} {:p {:build/alias [:r]}})]])
                     (catch clojure.lang.ExceptionInfo e (ex-message e)))]
        (is (str/includes? msg "tagaholic-me-index.edn :classes :A\n  g1: {:block/title \"A\"}\n  g2: {:block/title \"A2\"}"))
        (is (str/includes? msg "my-schema-org-index.edn :properties :p"))
        (is (not (str/includes? msg ":B")))))
    (testing "overrides replace their keys without conflicting, and add entries missing from graphs"
      (is (= {:A {:block/title "A" :build/alias [:Z]} :C {:build/alias [:Y]}}
             (get-in (#'my-schema/merge-graph-indexes
                      {:tagaholic {:classes {:A {:build/alias [:Z]} :C {:build/alias [:Y]}}}}
                      [["g1" (graph-result {:A {:block/title "A" :build/alias [:X]}} {})]
                       ["g2" (graph-result {:A {:block/title "A"}} {})]])
                     [:tagaholic :classes]))))
    (testing "::dissoc removes entries, even ones that differ across graphs"
      (is (= {:A {:block/title "A"}}
             (get-in (#'my-schema/merge-graph-indexes
                      {:tagaholic {:classes {::my-schema/dissoc [:B :C]}}}
                      [["g1" (graph-result {:A {:block/title "A"} :B {:block/title "B"}} {})]
                       ["g2" (graph-result {:A {:block/title "A"} :B {:block/title "B2"} :C {}} {})]])
                     [:tagaholic :classes]))))))

(def ^:private migrate-graph
  "A graph with a user referrerURL property plus a tagaholic.me class and a
  schema.org property whose names are also in tagaholic-me-index.edn"
  {:classes [["LargeLanguageModel" :tagaholic.me.class/LargeLanguageModel fake/person-uuid]]
   :properties [["referrerURL" :user.property/referrerURL-x fake/alt-uuid :url]
                ["parent" :schema.property/parent fake/kid-uuid :node]]
   :valued {:user.property/referrerURL-x [(assoc fake/block-entity :user.property/referrerURL-x {:block/title "https://x.com"})]}
   :aliases [[:user.property/referrerURL-x fake/alias-uuid]]
   :db-ids {:user.property/referrerURL-x 11}
   :existing #{:schema.property/url}
   :uuids {:tagaholic.me.property/referrerURL fake/new-person-uuid}})

(deftest migrate-pretend
  (testing "only user classes and properties are previewed"
    (let [{:keys [out imports shells]} (fake/run migrate-graph #(#'my-schema/migrate {:graph "g" :pretend true}))]
      (is (= [] imports shells))
      (is (str/includes? out "- 1 properties: referrerURL\n- 0 classes: \n"))
      (is (str/includes? out "  referrerURL - 1\n"))))
  (testing "with --verbose, an alias moves onto a replacement not yet in the graph by its ident"
    (let [{:keys [out]} (fake/run (assoc migrate-graph :uuids {})
                                  #(#'my-schema/migrate {:graph "g" :pretend true :verbose true}))]
      (is (str/includes? out ":block/uuid :tagaholic.me.property/referrerURL")))))

(deftest migrate-user-property
  (let [{:keys [imports shells error]} (fake/run migrate-graph #(#'my-schema/migrate {:graph "g"}))
        [rename add copy] imports]
    (is (nil? error))
    (testing "renames the original and adds its tagaholic.me replacement"
      (is (= {:pages-and-blocks [{:page {:block/uuid fake/alt-uuid :block/title "logseq-my-schema.referrerURL" :build/properties {}}}]}
             rename))
      (is (contains? (:properties add) :tagaholic.me.property/referrerURL)))
    (testing "moves the value and alias onto the replacement, declared with the index's type"
      (is (= {:properties {:tagaholic.me.property/referrerURL
                           {:block/title "referrerURL"
                            :logseq.property/type (get-in (#'my-schema/read-my-index) [:properties :referrerURL :logseq.property/type])
                            :db/cardinality :db.cardinality/one}}
              :pages-and-blocks
              [{:page {:block/uuid fake/new-person-uuid :block/alias #{[:block/uuid fake/alias-uuid]} :build/properties {}}}
               {:page {:block/uuid fake/page-uuid}
                :blocks [{:block/uuid fake/block-uuid :block/title "b"
                          :build/properties {:tagaholic.me.property/referrerURL "https://x.com"}}]}]}
             copy)))
    (testing "keeps the original by default"
      (is (= [] shells))))
  (testing "removes the original's value and then the original with --delete-existing"
    (is (= [["logseq" "upsert" "block" "-g" "g" "--id" "21" "--remove-properties" "[:user.property/referrerURL-x]"]
            ["logseq" "remove" "property" "-g" "g" "--id" "11"]]
           (:shells (fake/run migrate-graph #(#'my-schema/migrate {:graph "g" :delete-existing true})))))))
