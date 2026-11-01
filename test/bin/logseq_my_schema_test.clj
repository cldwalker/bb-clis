(ns bin.logseq-my-schema-test
  (:require [cldwalker.bb-clis.bin.logseq-my-schema :as my-schema]
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
                      :build/class-extends [:schema.class/Thing]}}
   :properties {:forApp {:logseq.property/type :node
                         :db/cardinality :many
                         :build/properties {:url "https://tagaholic.me/schema/forApp.html"
                                            :meta "A note"}}
                :meta {:logseq.property/type :default
                       :build/properties {:url "https://tagaholic.me/schema/meta.html"}}}})

(def ^:private url-anchor
  {:block/title "url" :logseq.property/type :url :db/cardinality :db.cardinality/one})

(def ^:private child-entry
  {:block/title "Child"
   :build/properties {:schema.property/url "https://tagaholic.me/schema/Child.html"}
   :build/class-extends #{:tagaholic.me.class/Parent}})

(defn- build-add-edn
  "Runs build-add-edn on `args` against a fake graph that already has
  schema.org's url and Thing plus the given index classes and properties"
  [args & {:keys [graph-classes graph-props]}]
  (let [idents (into #{:schema.property/url :schema.class/Thing}
                     (concat (map #(keyword "tagaholic.me.class" (name %)) graph-classes)
                             (map #(keyword "tagaholic.me.property" (name %)) graph-props)))
        existing-titles (fn [ks] (fn [_graph titles] (set (filter (set (map name ks)) titles))))]
    (with-redefs [logseq-util/existing-idents (fn [_graph ks] (set (filter idents ks)))
                  logseq-util/existing-user-class-titles (existing-titles graph-classes)
                  logseq-util/existing-property-titles (existing-titles graph-props)
                  cli-util/error (fn [& msgs] (throw (ex-info (str/join " " msgs) {})))]
      (#'my-schema/build-add-edn index "graph" args))))

(deftest build-add-edn-new-class
  (testing "adds the class and its local ancestors with qualified idents"
    (is (= {:new-classes #{:Child :Parent}
            :edn {:classes {:tagaholic.me.class/Child child-entry
                            :tagaholic.me.class/Parent
                            {:block/title "Parent"
                             :build/properties {:schema.property/url "https://tagaholic.me/schema/Parent.html"}
                             :build/class-extends #{:schema.class/Thing}}}
                  :properties {:schema.property/url url-anchor}}}
           (-> (build-add-edn [:Child])
               (update :new-classes set)
               (dissoc :new-props))))))

(deftest build-add-edn-new-property
  (is (= {:new-classes []
          :new-props [:forApp :meta]
          :edn {:properties {:tagaholic.me.property/forApp
                             {:block/title "forApp"
                              :logseq.property/type :node
                              :db/cardinality :db.cardinality/many
                              :build/properties {:schema.property/url "https://tagaholic.me/schema/forApp.html"
                                                 :tagaholic.me.property/meta "A note"}}
                             :tagaholic.me.property/meta
                             {:block/title "meta"
                              :logseq.property/type :default
                              :db/cardinality :db.cardinality/one
                              :build/properties {:schema.property/url "https://tagaholic.me/schema/meta.html"}}
                             :schema.property/url url-anchor}}}
         (build-add-edn [:forApp]))))

(deftest build-add-edn-existing-class
  (testing "skips an ancestor already in the graph"
    (is (= {:new-classes [:Child]
            :new-props []
            :edn {:classes {:tagaholic.me.class/Child child-entry}
                  :properties {:schema.property/url url-anchor}}}
           (build-add-edn [:Child] :graph-classes [:Parent]))))
  (testing "errors when the class arg itself is in the graph"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exist: 'Child'"
                          (build-add-edn [:Child] :graph-classes [:Child :Parent])))))

(deftest build-add-edn-existing-property
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already exist: 'forApp'"
                        (build-add-edn [:forApp] :graph-props [:forApp]))))
