(ns cldwalker.bb-clis.bin.logseq-publish-graph
  "Publishes a Logseq graph's journals and pages as a static site of quickblog
  posts.

  `create` writes quickblog posts into a quickblog directory's posts/:

  * A post per journal page that has a top-level block that isn't a task. A
    post is named and dated after its journal's day, titled after its journal
    and tagged journal. Its body is the journal's blocks as a nested html list
    where each block shows its tags floated to the right and its properties in
    a drawer underneath it. Blocks tagged Task are hidden at every level.
  * A post per page that isn't built-in or an alias. A post is named after a
    slug of its page's title, dated after the page's creation and tagged page.
    Its body is the page's tags and properties, rendered like a journal block's.

  `rebuild` recreates posts and re-renders the whole site.

  Graph data comes from ~/logseq/graphs/GRAPH/graph.edn when it exists, which
  logseq-graph-backup writes, and otherwise from `logseq graph export`. Journal
  titles and page creation dates come from `logseq query`. Posts are written by
  `bb quickblog new` with the resources/logseq-publish-graph/post.md Selmer
  template."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.logseq :as logseq-util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.text Normalizer Normalizer$Form)
           (java.time Instant ZoneId)))

(def ^:private post-template "logseq-publish-graph/post.md")

(defn- read-resource-edn [resource]
  (or (some-> (io/resource resource) slurp edn/read-string)
      (cli-util/error "Unable to find" resource "on classpath")))

;; Links
;; =====

(defn- escape-html [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn- link-html [{:keys [title url external?]}]
  (str "<a" (when external? " class=\"external\" target=\"_blank\" rel=\"noopener\"")
       " href=\"" (escape-html url) "\">" (escape-html title) "</a>"))

(defn- slug
  "Converts a page `title` to a lowercase, url and file name safe slug e.g.
  aaron-sanchez-guerra for Aaron Sánchez-Guerra"
  [title]
  (let [s (-> (Normalizer/normalize (str title) Normalizer$Form/NFD)
              (str/replace #"\p{M}" "")
              str/lower-case
              (str/replace #"[^a-z0-9]+" "-")
              (str/replace #"^-|-$" ""))]
    (if (str/blank? s) "page" s)))

(defn- page-files
  "Maps lowercased `titles` to unique post file names without an extension.
  Titles with the same slug get a numeric suffix in title order"
  [titles]
  (into {}
        (mapcat (fn [[s titles']]
                  (map-indexed (fn [i title]
                                 [(str/lower-case title) (if (zero? i) s (str s "-" (inc i)))])
                               titles'))
                (group-by slug (sort titles)))))

(defn- journal-date
  "Converts a journal day e.g. 20260510 to its YYYY-MM-DD date"
  [day]
  (let [s (str day)]
    (str (subs s 0 4) "-" (subs s 4 6) "-" (subs s 6 8))))

(def ^:private logseq-schema-urls
  "Schema.org urls of built-in logseq properties that mean the same thing"
  {:logseq.property/description "https://schema.org/description"})

(defn- ident-link
  "Links a :schema or :tagaholic class or property `ident` to its url in its
  index.edn, and built-in properties in [[logseq-schema-urls]] to schema.org.
  Schema.org links are external. Returns nil for other idents"
  [indexes ident]
  (if-let [url (logseq-schema-urls ident)]
    {:title (name ident) :external? true :url url}
    (when-let [[_ site kind] (some->> (namespace ident)
                                      (re-matches #"(schema|tagaholic)\..*(class|property)"))]
      {:title (name ident)
       :external? (= "schema" site)
       :url (or (get-in indexes [site (if (= "class" kind) :classes :properties)
                                 (keyword (name ident)) :build/properties :url])
                (cli-util/error "No" (str site) "index.edn url found for" ident))})))

(defn- ident-html
  "Renders `ident` as `label`, linked when it has an [[ident-link]]"
  [ctx ident label]
  (if-let [link (ident-link (:indexes ctx) ident)]
    (link-html (assoc link :title label))
    (escape-html label)))

;; Nodes
;; =====

(defn- task? [block]
  (contains? (set (:build/tags block)) :logseq.class/Task))

(defn- empty-block? [block]
  (and (str/blank? (:block/title block))
       (every? (comp empty? block) [:build/tags :build/properties :build/children])))

(defn- publishable?
  "Journal `blocks` are published when one of them isn't a task or empty"
  [blocks]
  (boolean (some #(not (or (task? %) (empty-block? %))) blocks)))

(defn- page-node
  "Node for an export page with its title and the url of its post. An alias
  page links to what it's an alias for. Journals and pages without a post have
  no url"
  [ctx {:build/keys [journal] :block/keys [title uuid]}]
  (cond
    journal
    {:title (get-in ctx [:journal-titles journal] (journal-date journal))
     :url (when (contains? (:published-days ctx) journal)
            (str (journal-date journal) ".html"))}
    (get-in ctx [:alias-owners uuid])
    (assoc (get-in ctx [:alias-owners uuid]) :title title)
    :else
    {:title title
     :url (some-> (get-in ctx [:page-files (str/lower-case title)]) (str ".html"))}))

(defn- block-nodes
  "Maps uuids of `blocks` and their descendants to nodes that link to their
  page's post. Blocks hidden by being or being under a task have no url"
  [page-url hidden? blocks]
  (mapcat (fn [{:block/keys [uuid title] :as block}]
            (let [hidden? (or hidden? (task? block) (nil? page-url))]
              (cond->> (block-nodes page-url hidden? (:build/children block))
                uuid
                (cons [uuid {:title title
                             :url (when-not hidden? (str page-url "#" uuid))}]))))
          blocks))

(defn- graph-nodes
  "Maps uuids of an export's pages, blocks, classes and properties to nodes,
  which are maps with a :title and an optional :url"
  [ctx export]
  (into {}
        (concat
         (mapcat (fn [{:keys [page blocks]}]
                   (let [node (page-node ctx page)]
                     (cond->> (block-nodes (:url node) false blocks)
                       (:block/uuid page) (cons [(:block/uuid page) node]))))
                 (:pages-and-blocks export))
         (keep (fn [[ident {:block/keys [uuid title]}]]
                 (when uuid
                   [uuid (or (ident-link (:indexes ctx) ident) {:title title})]))
               (merge (:classes export) (:properties export))))))

(defn- alias-uuids [export]
  (set (map second (mapcat :block/alias (concat (map :page (:pages-and-blocks export))
                                                (vals (:classes export))
                                                (vals (:properties export)))))))

(defn- alias-owners
  "Maps alias uuids to nodes of the pages, classes and properties they're an
  alias for"
  [ctx export]
  (into {}
        (for [[ent node] (concat
                          (map (fn [{:keys [page]}] [page (page-node ctx page)])
                               (:pages-and-blocks export))
                          (map (fn [[ident ent]]
                                 [ent (or (ident-link (:indexes ctx) ident) {:title (:block/title ent)})])
                               (merge (:classes export) (:properties export))))
              [_ uuid] (:block/alias ent)]
          [uuid node])))

;; Text
;; ====

(def ^:private ref-re #"\[\[(.+?)\]\]")

(defn- ref-node
  "Node of a page ref's text, which is a uuid or a page title"
  [ctx ref]
  (if-let [uuid (parse-uuid ref)]
    (get-in ctx [:nodes uuid])
    (if-let [day (get-in ctx [:journal-days ref])]
      (page-node ctx {:build/journal day})
      (if-let [page (get-in ctx [:pages-by-name (str/lower-case ref)])]
        (page-node ctx page)
        {:title ref}))))

(defn- plain-title
  "Replaces page refs in `title` with their titles. `seen` refs aren't
  replaced to avoid cycles"
  [ctx title seen]
  (str/replace (str title) ref-re
               (fn [[match ref]]
                 (if-let [node (and (not (seen ref)) (ref-node ctx ref))]
                   (plain-title ctx (:title node) (conj seen ref))
                   match))))

(defn- ref-html [ctx ref]
  (if-let [node (ref-node ctx ref)]
    (let [title (plain-title ctx (:title node) #{ref})]
      (if (:url node)
        (link-html (assoc node :title title))
        (escape-html title)))
    (escape-html (str "[[" ref "]]"))))

(def ^:private inline-re
  "Matches a page ref, a markdown link or a url"
  #"\[\[(.+?)\]\]|\[([^\]]+)\]\((https?://[^)\s]+)\)|https?://[^\s<>\"]+")

(defn- inline-html [ctx [match ref link-title link-url]]
  (cond
    ref (ref-html ctx ref)
    link-url (link-html {:title link-title :url link-url :external? true})
    :else (let [[_ url trailing] (re-matches #"(.*?)([.,;:!?)]*)" match)]
            (str (link-html {:title url :url url :external? true})
                 (escape-html trailing)))))

(defn- text-html [s]
  (str/replace (escape-html s) #"\r?\n" "<br>"))

(defn- render-text
  "Renders block or property `text` as html, linking its page refs, markdown
  links and urls. Newlines become <br> so the html has no blank lines"
  [ctx text]
  (let [m (re-matcher inline-re text)]
    (loop [start 0 out []]
      (if (.find m)
        (recur (.end m) (conj out
                              (text-html (subs text start (.start m)))
                              (inline-html ctx (re-groups m))))
        (apply str (conj out (text-html (subs text start))))))))

;; Blocks
;; ======

(declare blocks-html)

(defn- keyword-label
  "Label of a keyword property value e.g. todo for :logseq.property/status.todo"
  [k]
  (last (str/split (name k) #"\.")))

(defn- value-html [ctx v]
  (cond
    (string? v) (render-text ctx v)
    (keyword? v) (ident-html ctx v (keyword-label v))
    (and (vector? v) (= :block/uuid (first v))) (ref-html ctx (str (second v)))
    (and (vector? v) (= :build/page (first v)))
    (let [node (page-node ctx (second v))]
      (if (:url node) (link-html node) (escape-html (:title node))))
    :else (escape-html (pr-str v))))

(defn- property-html
  "Renders a property as its name and values. Block values are a nested list"
  [ctx [k v]]
  (let [{blocks true values false}
        (group-by #(boolean (and (map? %) (:build/property-value %)))
                  (if (set? v) (sort-by str v) [v]))]
    (str "<li><span class=\"property-name\">" (ident-html ctx k (name k)) "</span>: "
         (str/join ", " (map #(value-html ctx %) values))
         (blocks-html ctx blocks)
         "</li>")))

(defn- properties-html [ctx properties]
  (when (seq properties)
    (str "<ul class=\"block-properties\">"
         (str/join (map #(property-html ctx %) (sort-by (comp name key) properties)))
         "</ul>")))

(defn- tags-html [ctx tags]
  (when (seq tags)
    (str "<span class=\"block-tags\">"
         (str/join " " (map #(ident-html ctx % (str "#" (name %))) (sort-by name tags)))
         "</span>")))

(defn- block-url
  "A block's url property, which its title links to instead of being shown as a
  property"
  [block]
  (let [url (get-in block [:build/properties :schema.property/url])]
    (when (string? url) url)))

(defn- title-html [ctx {:block/keys [title] :as block}]
  (if-let [url (block-url block)]
    (link-html {:title (if (str/blank? title)
                         url
                         (str/replace (plain-title ctx title #{}) #"\s*\n\s*" " "))
                :url url})
    (render-text ctx (str title))))

(defn- block-html
  "Renders a block with its tags, properties and children, or nil when it's
  empty or a task. Counts rendered blocks and hidden tasks in :stats"
  [ctx {:block/keys [uuid] :as block}]
  (cond
    (task? block) (do (swap! (:stats ctx) update :tasks inc) nil)
    (empty-block? block) nil
    :else
    (do (swap! (:stats ctx) update :blocks inc)
        (str "<li" (when uuid (str " id=\"" uuid "\"")) ">"
             (title-html ctx block)
             (tags-html ctx (:build/tags block))
             (properties-html ctx (cond-> (:build/properties block)
                                    (block-url block) (dissoc :schema.property/url)))
             (blocks-html ctx (:build/children block))
             "</li>"))))

(defn- blocks-html [ctx blocks]
  (when-let [items (seq (keep #(block-html ctx %) blocks))]
    (str "<ul>\n" (str/join "\n" items) "\n</ul>")))

;; Posts
;; =====

(defn- read-edn-file [file]
  (edn/read-string {:default tagged-literal} (slurp (fs/file file))))

(defn- export-graph
  "Reads `graph`'s :graph-human edn export from its graph.edn if it exists or
  else from `logseq graph export`. `fresh` always exports"
  [graph fresh]
  (let [graph-edn (fs/path (fs/home) "logseq" "graphs" graph "graph.edn")]
    (if (and (not fresh) (fs/exists? graph-edn))
      (read-edn-file graph-edn)
      (let [file (fs/create-temp-file {:prefix "logseq-publish-graph" :suffix ".edn"})
            export (try
                     (when (zero? (:exit (shell {:out :string :continue true}
                                                "logseq" "graph" "export" "-g" graph "-t" "edn"
                                                "-e" "{:export-type :graph-human}" "--file" (str file))))
                       (read-edn-file file))
                     (finally (fs/delete-if-exists file)))]
        (or export (cli-util/error "Unable to export graph" graph))))))

(defn- journal-titles [graph]
  (into {} (logseq-util/logseq-query
            graph
            '[:find ?day ?title :where [?p :block/journal-day ?day] [?p :block/title ?title]]
            nil)))

(defn- page-dates
  "Maps lowercased page names to the YYYY-MM-DD local dates they were created"
  [graph]
  (into {}
        (map (fn [[page-name created]]
               [page-name (str (.toLocalDate (.atZone (Instant/ofEpochMilli created)
                                                      (ZoneId/systemDefault))))]))
        (logseq-util/logseq-query
         graph
         '[:find ?name ?created :where [?p :block/name ?name] [?p :block/created-at ?created]]
         nil)))

(defn- published-page?
  "Non-journal pages are published unless they're built-in or an alias"
  [alias-uuids page]
  (not (or (:build/journal page)
           (get-in page [:build/properties :logseq.property/built-in?])
           (get-in page [:build/properties :logseq.property.user/email])
           (contains? alias-uuids (:block/uuid page)))))

(defn- graph-context
  "Context for rendering `export`'s posts and links between them"
  [graph export]
  (let [pages (map :page (:pages-and-blocks export))
        titles (journal-titles graph)
        aliases (alias-uuids export)
        ctx {:indexes {"schema" (read-resource-edn "schema-org-index.edn")
                       "tagaholic" (read-resource-edn "tagaholic-me-index.edn")}
             :journal-titles titles
             :journal-days (zipmap (vals titles) (keys titles))
             ;; From all journals so links between journals work when only some are selected
             :published-days (set (keep #(when (publishable? (:blocks %)) (get-in % [:page :build/journal]))
                                        (:pages-and-blocks export)))
             :alias-uuids aliases
             :page-files (page-files (map :block/title (filter #(published-page? aliases %) pages)))
             :pages-by-name (into {} (map (juxt (comp str/lower-case :block/title) identity)
                                          (remove :build/journal pages)))
             :page-dates (page-dates graph)}
        ctx (assoc ctx :alias-owners (alias-owners ctx export))]
    (assoc ctx :nodes (graph-nodes ctx export))))

(defn- page-description
  "A page's description as one line of plain text for a post's Description,
  with page refs and markdown links replaced by their titles"
  [ctx page]
  (when-let [description (get-in page [:build/properties :logseq.property/description])]
    (-> (plain-title ctx description #{})
        (str/replace #"\[([^\]]+)\]\(https?://[^)\s]+\)" "$1")
        (str/replace #"\s+" " ")
        str/trim)))

(defn- journal-post [ctx {:keys [page blocks]}]
  (let [day (:build/journal page)
        date (journal-date day)
        stats (atom {:blocks 0 :tasks 0})
        body (blocks-html (assoc ctx :stats stats) blocks)]
    (merge {:type :journal
            :arg date
            :date date
            :file (str date ".md")
            :title (or (get-in ctx [:journal-titles day])
                       (cli-util/error "No title found for journal" date))
            :tags ["journal"]
            :logseq-tags (tags-html ctx (:build/tags page))
            :description (page-description ctx page)
            :publish? (publishable? blocks)
            :entities (count (remove #(or (task? %) (empty-block? %)) blocks))
            :body body}
           @stats)))

(defn- page-post
  "Post of a page's properties with its tags beside its title. Unlike blocks, a
  page's url is shown as a property"
  [ctx {:block/keys [title] :as page}]
  (let [properties (properties-html ctx (:build/properties page))]
    {:type :page
     :arg title
     :date (or (get-in ctx [:page-dates (str/lower-case title)])
               (cli-util/error "No creation date found for page" title))
     :file (str (get-in ctx [:page-files (str/lower-case title)]) ".md")
     :title title
     :tags ["page"]
     :logseq-tags (tags-html ctx (:build/tags page))
     :description (page-description ctx page)
     :publish? true
     :body (when properties (str "<div class=\"page-properties\">" properties "</div>"))
     :blocks 0
     :tasks 0}))

(defn- select-posts
  "Selects `posts` whose journal date or page title is in `args`, or all
  `posts` when there are no `args`. Errors on args that aren't in the graph"
  [posts args]
  (if (empty? args)
    posts
    (let [by-arg (into {} (map (juxt (comp str/lower-case :arg) identity) posts))
          args' (distinct (map str/lower-case args))]
      (when-let [unknown (seq (remove by-arg args'))]
        (cli-util/error "These are not journal dates or pages in the graph:"
                        (pr-str (vec (sort unknown)))))
      (map by-arg args'))))

(defn- graph-posts
  "Reads `graph` and returns a post for each of its journals and published
  pages selected by `args`. Only posts with :publish? are written"
  [{:keys [graph fresh args]}]
  (let [export (export-graph graph fresh)
        ctx (graph-context graph export)
        {journals true pages false} (group-by (comp boolean :build/journal :page)
                                              (:pages-and-blocks export))]
    (select-posts (concat (map #(journal-post ctx %) (sort-by (comp :build/journal :page) journals))
                          (->> (map :page pages)
                               (filter #(published-page? (:alias-uuids ctx) %))
                               (sort-by :block/title)
                               (map #(page-post ctx %))))
                  args)))

(defn- site-dir [dir]
  (let [dir (fs/expand-home dir)]
    (when-not (fs/directory? dir) (cli-util/error "Directory" (str dir) "does not exist"))
    dir))

(defn- post-path [dir post]
  (fs/path dir "posts" (:file post)))

(defn- quickblog-new-args
  "Args for `bb quickblog new`. Options it doesn't know are passed to the post
  template"
  [template-file {:keys [file title date tags logseq-tags description entities body]}]
  (cond-> (into ["bb" "quickblog" "new" "--file" file "--title" title "--date" date
                 "--template-file" template-file "--tags"]
                tags)
    logseq-tags (conj "--logseq-tags" logseq-tags)
    description (conj "--description" description)
    entities (conj "--entities" (str entities))
    body (conj "--body" body)))

(defn- write-posts
  "Writes `posts` with `bb quickblog new`, or only lists them when `pretend`,
  and reports created journals, pages and blocks and tasks hidden from them and
  from `unpublished` journals"
  [posts unpublished dir pretend]
  (let [template-file (str (io/file (io/resource post-template)))
        counts (frequencies (map :type posts))]
    (doseq [post posts]
      (if pretend
        (println "Would create" (str (post-path dir post)))
        (apply shell {:dir (str dir)} (quickblog-new-args template-file post))))
    (println (if pretend "Would create" "Created")
             (get counts :journal 0) "journals and" (get counts :page 0) "pages with"
             (reduce + (map :blocks posts)) "blocks in" (str (fs/path dir "posts")))
    (println "Hid" (reduce + (map :tasks (concat posts unpublished))) "tasks")))

(defn- create [{:keys [dir pretend] :as opts}]
  (let [dir (site-dir dir)
        {publish true unpublished false} (group-by :publish? (graph-posts opts))
        {existing true new-posts false} (group-by #(fs/exists? (post-path dir %)) publish)]
    (write-posts new-posts unpublished dir pretend)
    (when (seq existing)
      (println "Skipped" (count existing) "posts that already exist"))))

(defn- run-quickblog [dir pretend & args]
  (let [cmd (into ["bb" "quickblog"] args)]
    (if pretend
      (println "Would run" (pr-str (str/join " " cmd)) "in" (str dir))
      (apply shell {:dir (str dir)} cmd))))

(defn- rebuild [{:keys [dir pretend] :as opts}]
  (let [dir (site-dir dir)
        posts (graph-posts opts)
        {publish true unpublished false} (group-by :publish? posts)
        ;; Includes unpublished journals so their stale posts are removed
        existing-files (filter fs/exists? (map #(post-path dir %) posts))
        ;; quickblog watch's cache, which clean doesn't remove
        watch-cache-dir (fs/path dir ".work" "dev")]
    (run-quickblog dir pretend "clean")
    (if pretend
      (println "Would delete" (count existing-files) "posts and" (str watch-cache-dir))
      (do (run! fs/delete existing-files)
          (fs/delete-tree watch-cache-dir)))
    (write-posts publish unpublished dir pretend)
    (run-quickblog dir pretend "render")))

(def ^:private common-spec
  {:graph {:alias :g
           :coerce :string
           :default "personal"
           :desc "Graph to publish"
           :complete-fn logseq-util/complete-graphs}
   :dir {:alias :d
         :default "~/code/repo/knowledge-graph"
         :desc "quickblog directory whose posts/ is written to"}
   :pretend {:alias :n
             :coerce :boolean
             :desc "Only report what would be done"}
   :fresh {:alias :f
           :coerce :boolean
           :desc "Export the graph even if ~/logseq/graphs/GRAPH/graph.edn exists"}
   :args {:coerce [:string]
          :desc "Journal dates as YYYY-MM-DD or page titles. Defaults to all journals and pages"}})

(def ^:private table
  [{:cmds ["create"]
    :fn (fn [{:keys [opts]}] (create opts))
    :spec common-spec
    :args->opts (repeat :args)
    :doc "Create a quickblog post for each journal that has a top-level block
  that isn't a task and for each page that isn't built-in or an alias, or only
  the given ones. Existing posts are left unchanged."}
   {:cmds ["rebuild"]
    :fn (fn [{:keys [opts]}] (rebuild opts))
    :spec common-spec
    :args->opts (repeat :args)
    :doc "Recreate posts for all or the given journals and pages and render the
  site from scratch: runs `bb quickblog clean`, deletes their posts and watch's
  cache, creates them and runs `bb quickblog render`. Other posts are left
  alone. Stop `bb quickblog watch` first."}])

(defn -main [& args]
  (cli/dispatch table args {:prog "logseq-publish-graph" :help true}))
