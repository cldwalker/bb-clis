(ns bin.logseq-publish-graph-test
  (:require [babashka.fs :as fs]
            [cldwalker.bb-clis.bin.logseq-publish-graph :as publish]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.logseq :as logseq-util]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [selmer.parser :as selmer]))

(def ^:private durham-uuid #uuid "00000000-0000-0000-0000-000000000001")
(def ^:private north-carolina-uuid #uuid "00000000-0000-0000-0000-000000000002")
(def ^:private nc-uuid #uuid "00000000-0000-0000-0000-000000000003")
(def ^:private canopy-uuid #uuid "00000000-0000-0000-0000-000000000004")

(defn- schema-class [title & parents]
  (cond-> {:block/title title
           :build/properties {:schema.property/url (str "https://schema.org/" title)}}
    (seq parents) (assoc :build/class-extends (set (map #(keyword "schema.class" %) parents)))))

(def ^:private export
  "A :graph-human export with a published and a tasks-only journal, a page with
  an alias, a built-in page, a page named like a class and a class hierarchy
  where City has two parents"
  {:classes {:schema.class/Thing (schema-class "Thing")
             :schema.class/CreativeWork (schema-class "CreativeWork" "Thing")
             :schema.class/Article (schema-class "Article" "CreativeWork")
             :schema.class/Place (schema-class "Place" "Thing")
             :schema.class/AdministrativeArea (schema-class "AdministrativeArea" "Place")
             :schema.class/City (schema-class "City" "AdministrativeArea" "Place")
             ;; Not an ancestor of a tag so it has no class post
             :schema.class/Event (schema-class "Event" "Thing")}
   :properties {}
   :pages-and-blocks
   [{:page {:build/journal 20260510}
     :blocks [{:block/title "Tree canopy grows in [[00000000-0000-0000-0000-000000000001]]"
               :block/uuid canopy-uuid
               :build/tags #{:schema.class/Article}
               :build/properties {:schema.property/url "https://example.com/canopy"
                                  :logseq.property/description "About [[00000000-0000-0000-0000-000000000003]]"}}
              {:block/title "Untagged note"
               :build/children [{:block/title "Hidden chore"
                                 :build/tags #{:logseq.class/Task}
                                 :build/children [{:block/title "Under a chore"
                                                   :build/tags #{:schema.class/Article}}]}
                                {:block/title "Nested article on [[Durham]]"
                                 :build/tags #{:schema.class/Article}}]}]}
    {:page {:build/journal 20260511}
     :blocks [{:block/title "Only a chore" :build/tags #{:logseq.class/Task}}]}
    {:page {:block/title "Durham"
            :block/uuid durham-uuid
            :build/tags #{:schema.class/City}
            :build/properties {:schema.property/url "https://en.wikipedia.org/wiki/Durham"}}}
    {:page {:block/title "North Carolina"
            :block/uuid north-carolina-uuid
            :block/alias #{[:block/uuid nc-uuid]}}}
    {:page {:block/title "NC" :block/uuid nc-uuid}}
    {:page {:block/title "Contents" :build/properties {:logseq.property/built-in? true}}}
    {:page {:block/title "Article"}}]})

(defn- fake-query
  "Answers graph-posts' journal title and page creation date queries"
  [_graph query _inputs]
  (if (some #{:block/journal-day} (flatten query))
    #{[20260510 "May 10th, 2026"] [20260511 "May 11th, 2026"]}
    ;; Midday UTC so the local date is the same in every time zone
    (let [created (.toEpochMilli (java.time.Instant/parse "2026-06-01T12:00:00Z"))]
      (map #(vector % created) ["durham" "north carolina" "nc" "article" "city"]))))

(defn- graph-posts-by-file []
  (with-redefs-fn {#'publish/export-graph (fn [_graph _fresh] export)
                   #'logseq-util/logseq-query fake-query}
    #(into {} (map (juxt :file identity) (#'publish/graph-posts {:graph "g"})))))

(deftest journal-posts
  (let [posts (graph-posts-by-file)
        {:keys [body] :as journal} (posts "2026-05-10.md")]
    (testing "journals are named, dated and titled after their day"
      (is (= {:date "2026-05-10" :title "May 10th, 2026" :tags ["journal"] :publish? true}
             (select-keys journal [:date :title :tags :publish?]))))
    (testing "journals with only tasks aren't published"
      (is (false? (:publish? (posts "2026-05-11.md")))))
    (testing "tasks and blocks under them are hidden at any level"
      (is (not (str/includes? body "chore")))
      (is (= {:blocks 3 :tasks 1} (select-keys journal [:blocks :tasks]))))
    (testing "a block's url is its title's link instead of a property"
      (is (str/includes? body "<a href=\"https://example.com/canopy\">Tree canopy grows in Durham</a>"))
      (is (not (str/includes? body "https://schema.org/url"))))
    (testing "page refs by uuid or title and aliases link to page posts"
      (is (str/includes? body "<a href=\"durham.html\">Durham</a>"))
      (is (str/includes? body "<a href=\"north-carolina.html\">NC</a>")))
    (testing "block tags link to their class posts"
      (is (str/includes? body "<a href=\"Article.html\">#Article</a>")))))

(deftest page-posts
  (let [posts (graph-posts-by-file)]
    (testing "page posts are named after a slug of their title"
      (is (= "Durham" (:title (posts "durham.md"))))
      (is (= "North Carolina" (:title (posts "north-carolina.md")))))
    (testing "built-in and alias pages aren't published"
      (is (nil? (posts "contents.md")))
      (is (nil? (posts "nc.md"))))
    (testing "a page named like a class gets a suffix since the class post has its name"
      (is (= :page (:type (posts "article-2.md"))))
      (is (= :class (:type (posts "Article.md")))))
    (testing "a page's url is a property and its tags link to class posts"
      (let [{:keys [body logseq-tags]} (posts "durham.md")]
        (is (str/includes? body "href=\"https://schema.org/url\""))
        (is (str/includes? logseq-tags "<a href=\"City.html\">#City</a>"))))))

(deftest class-posts
  (let [posts (graph-posts-by-file)
        journal-body (:body (posts "2026-05-10.md"))
        {:keys [body] :as article} (posts "Article.md")]
    (testing "class posts are previews so they stay out of the index, archive and feeds"
      (is (true? (:preview article))))
    (testing "a class post counts what it tags, skipping blocks under tasks"
      (is (str/includes? body "<p class=\"class-count\">2 entities</p>"))
      (is (not (str/includes? body "chore"))))
    (testing "block rows link to anchors that exist in their journal post"
      (doseq [id [(str canopy-uuid) "b-1-1"]]
        (is (str/includes? body (str "href=\"2026-05-10.html#" id "\"")))
        (is (str/includes? journal-body (str "id=\"" id "\"")))))
    (testing "page rows link to the page post with its type and creation date"
      (is (str/includes? (:body (posts "City.md"))
                         "<tr><td><a href=\"durham.html\">Durham</a></td><td>Page</td><td>2026-06-01</td></tr>")))
    (testing "the classes table lists classes that tag something by how many they tag"
      (let [classes-body (:body (posts "classes.md"))
            table (subs classes-body (str/index-of classes-body "<table"))]
        (is (re-find #"(?s)Article\.html\">Article</a></td><td>2</td>.*City\.html\">City</a></td><td>1</td>" table))
        (is (not (str/includes? table "AdministrativeArea")))))))

(deftest class-hierarchy
  (let [posts (graph-posts-by-file)
        link #(str "<a href=\"" % ".html\">" % "</a>")]
    (testing "ancestors of tags get class posts and other classes don't"
      (is (= #{"Thing.md" "CreativeWork.md" "Article.md" "Place.md" "AdministrativeArea.md" "City.md"}
             (set (map :file (filter #(= :class (:type %)) (vals posts))))))
      (is (str/includes? (:body (posts "Thing.md")) "<p class=\"class-count\">0 entities</p>")))
    (testing "the hierarchy nests classes under each of their parents, sorted by name, and
              classes with subclasses collapse"
      (let [node #(str "<details open><summary>" (link %1) "</summary><ul>" (apply str %&) "</ul></details>")]
        (is (str/includes? (:body (posts "classes.md"))
                           (str "<ul class=\"class-hierarchy\"><li>"
                                (node "Thing"
                                      (str "<li>" (node "CreativeWork" (str "<li>" (link "Article") "</li>")) "</li>")
                                      (str "<li>" (node "Place"
                                                        (str "<li>" (node "AdministrativeArea" (str "<li>" (link "City") "</li>")) "</li>")
                                                        (str "<li>" (link "City") "</li>"))
                                           "</li>"))
                                "</li></ul>")))))
    (testing "breadcrumbs lead from the root through a class's first parent"
      (is (str/includes? (:body (posts "City.md"))
                         (str "<p class=\"breadcrumbs\">" (link "Thing") " &gt; " (link "Place") " &gt; "
                              (link "AdministrativeArea") " &gt; City</p>")))
      (is (not (str/includes? (:body (posts "Thing.md")) "breadcrumbs"))))))

(deftest select-posts
  (let [posts (vals (graph-posts-by-file))]
    (testing "selects journals by date and pages and classes by case-insensitive name"
      (is (= ["2026-05-10.md" "durham.md" "City.md"]
             (map :file (#'publish/select-posts posts ["2026-05-10" "durham" "city"])))))
    (testing "errors on args that aren't in the graph"
      (with-redefs [cli-util/error (fn [& msgs] (throw (ex-info (str/join " " msgs) {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\[\"nope\"\]"
                              (#'publish/select-posts posts ["nope"])))))))

(defn- render-post-file
  "Writes `post` to `dir`'s posts/ with the post template, like `bb quickblog new`"
  [dir post]
  (let [file (fs/file dir "posts" (:file post))]
    (spit file (selmer/render (slurp (io/resource "logseq-publish-graph/post.md"))
                              (assoc post :summary (#'publish/summary-post? post))))
    file))

(deftest remove-stale-summaries
  (let [dir (fs/create-temp-dir)
        _ (fs/create-dirs (fs/path dir "posts"))
        current {:type :class :file "Article.md" :title "Article" :date "2026-06-01" :tags [] :body "<p>x</p>"}
        stale-file (render-post-file dir (assoc current :file "Gone.md" :title "Gone"))
        current-file (render-post-file dir current)
        page-file (render-post-file dir {:type :page :file "durham.md" :title "Durham" :date "2026-06-01" :tags ["page"]})]
    (try
      (testing "pretend only reports stale class posts"
        (is (str/includes? (with-out-str (#'publish/remove-stale-summaries dir [current] true)) "Gone.md"))
        (is (fs/exists? stale-file)))
      (testing "deletes class posts that aren't current and leaves other posts alone"
        (with-out-str (#'publish/remove-stale-summaries dir [current] false))
        (is (not (fs/exists? stale-file)))
        (is (fs/exists? current-file))
        (is (fs/exists? page-file)))
      (finally (fs/delete-tree dir)))))
