(ns bin.logseq-schema-test
  (:require [bin.logseq-migrate-fake :as fake]
            [cldwalker.bb-clis.bin.logseq-schema :as schema]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private graph-state
  "A graph with user Person and alternateName conflicts, a user class extending
  Person, a non-conflicting user class, and an alias on Person"
  {:classes [["Person" :user.class/Person-x fake/person-uuid]
             ["Kid" :user.class/Kid-z fake/kid-uuid]]
   :properties [["alternateName" :user.property/alternateName-y fake/alt-uuid :default]]
   :tagged {:user.class/Person-x [fake/page-entity]}
   :valued {:user.property/alternateName-y [(assoc fake/block-entity :user.property/alternateName-y {:block/title "Al"})]}
   :extends [[:user.class/Kid-z fake/kid-uuid :user.class/Person-x]]
   :aliases [[:user.class/Person-x fake/alias-uuid]]
   :db-ids {:user.class/Person-x 10 :user.property/alternateName-y 11}
   :existing #{:schema.class/Thing :schema.property/url}
   :uuids {:schema.class/Person fake/new-person-uuid}})

(defn- run-migrate [opts]
  (fake/run graph-state #(#'schema/migrate opts)))

(deftest migrate-pretend
  (let [{:keys [out imports shells]} (run-migrate {:graph "g" :pretend true})]
    (testing "previews conflicts and their association counts without changing the graph"
      (is (= [] imports))
      (is (= [] shells))
      (is (str/includes? out "- 1 properties: alternateName\n- 1 classes: Person\n"))
      (is (str/includes? out "2 entities associated 2 times"))
      (is (str/includes? out "  alternateName - 1\n  Person - 1\n")))))

(deftest migrate-imports
  (let [{:keys [imports]} (run-migrate {:graph "g"})
        [rename add copy] imports]
    (is (= 3 (count imports)))
    (testing "renames originals out of the way"
      (is (= {:pages-and-blocks [{:page {:block/uuid fake/person-uuid :block/title "logseq-schema.Person" :build/properties {}}}
                                 {:page {:block/uuid fake/alt-uuid :block/title "logseq-schema.alternateName" :build/properties {}}}]}
             rename)))
    (testing "adds the schema.org replacements"
      (is (= #{:schema.class/Person} (set (keys (:classes add)))))
      (is (= #{:schema.property/alternateName :schema.property/url} (set (keys (:properties add))))))
    (testing "copies tags, values, extends and aliases onto the replacements"
      (is (= {:properties {:schema.property/alternateName
                           {:block/title "alternateName" :logseq.property/type :default :db/cardinality :db.cardinality/one}}
              :pages-and-blocks
              [{:page {:block/uuid fake/page-uuid :build/tags [:schema.class/Person]}}
               {:page {:block/uuid fake/kid-uuid :build/properties {:logseq.property.class/extends #{:schema.class/Person}}}}
               {:page {:block/uuid fake/new-person-uuid :block/alias #{[:block/uuid fake/alias-uuid]} :build/properties {}}}
               {:page {:block/uuid fake/page-uuid}
                :blocks [{:block/uuid fake/block-uuid :block/title "b"
                          :build/properties {:schema.property/alternateName "Al"}}]}]}
             copy)))))

(deftest migrate-keeps-originals
  (testing "without --delete-existing, the originals and their associations are left alone"
    (let [{:keys [out shells error]} (run-migrate {:graph "g"})]
      (is (nil? error))
      (is (= [] shells))
      (is (str/includes? out "Kept the originals")))))

(deftest migrate-removes-originals
  (let [{:keys [shells]} (run-migrate {:graph "g" :delete-existing true})]
    (is (= [["logseq" "upsert" "page" "-g" "g" "--id" "20" "--remove-tags" "[:user.class/Person-x]"]
            ["logseq" "upsert" "block" "-g" "g" "--id" "21" "--remove-properties" "[:user.property/alternateName-y]"]
            ["logseq" "remove" "property" "-g" "g" "--id" "11"]
            ["logseq" "remove" "tag" "-g" "g" "--id" "10"]]
           shells))))

(deftest migrate-incomplete
  (testing "errors without removing originals when entities are still associated"
    (let [{:keys [error shells]} (fake/run (assoc graph-state :remaining 1)
                                           #(#'schema/migrate {:graph "g" :delete-existing true}))]
      (is (str/includes? error "Migration incomplete"))
      (is (not-any? #(= "remove" (second %)) shells)))))

(deftest migrate-nothing
  (is (str/includes? (:out (fake/run (assoc graph-state :classes [] :properties []) #(#'schema/migrate {:graph "g"})))
                     "No user classes or properties found to migrate.")))

(deftest migrate-skips-index-entities
  (testing "classes and properties already from schema.org aren't conflicts"
    (let [state (-> graph-state
                    (update :classes conj ["Thing" :schema.class/Thing #uuid "00000000-0000-0000-0000-000000000030"])
                    (update :properties conj ["url" :schema.property/url #uuid "00000000-0000-0000-0000-000000000031" :url]))
          {:keys [out]} (fake/run state #(#'schema/migrate {:graph "g" :pretend true}))]
      (is (str/includes? out "- 1 properties: alternateName\n- 1 classes: Person\n")))))
