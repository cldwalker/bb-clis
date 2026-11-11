(ns bin.logseq-my-schema-test
  (:require [babashka.fs :as fs]
            [bin.logseq-migrate-fake :as fake]
            [cldwalker.bb-clis.bin.logseq-my-schema :as my-schema]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.logseq :as logseq-util]
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
      (#'my-schema/build-add-edn index "graph" args))))

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
   index :classes update-entries
   {:uuids (cond-> {:schema.class/SoftwareSourceCode code-uuid
                    :schema.class/Service service-uuid
                    :schema.class/Text #uuid "00000000-0000-0000-0000-000000000003"}
             meta-exists? (assoc :tagaholic.me.property/meta #uuid "00000000-0000-0000-0000-000000000004"))
    :aliases {:schema.class/SoftwareSourceCode #{"repo"} :schema.class/Text #{"String"}}
    :metas {:schema.class/Service "Old note" :schema.class/Text "Same note"}}))

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

(deftest graph-dirs
  (fs/with-temp-dir [dir {}]
    (doseq [d ["a/pages" "b/pages" "not-a-graph"]] (fs/create-dirs (fs/path dir d)))
    (testing "a directory of graphs returns each graph"
      (is (= ["a" "b"] (map (comp str fs/file-name) (#'my-schema/graph-dirs dir)))))
    (testing "a graph returns itself"
      (is (= [(fs/path dir "a")] (#'my-schema/graph-dirs (fs/path dir "a")))))))

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
