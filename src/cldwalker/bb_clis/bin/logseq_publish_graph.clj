(ns cldwalker.bb-clis.bin.logseq-publish-graph
  "Publishes a Logseq graph's journals as a static site of quickblog posts.

  `create` writes a quickblog post per journal page that has a top-level block
  that isn't a task into a quickblog directory's posts/. A post is named and
  dated after its journal's day, titled after its journal and tagged journal.
  Its body is the journal's blocks as a nested html list where each block shows
  its tags floated to the right and its properties in a drawer underneath it.
  Blocks tagged Task are hidden at every level. `rebuild` recreates posts and
  re-renders the whole site.

  Graph data comes from ~/logseq/graphs/GRAPH/graph.edn when it exists, which
  logseq-graph-backup writes, and otherwise from `logseq graph export`. Journal
  titles come from `logseq query`. Posts are written by `bb quickblog new` with
  the resources/logseq-publish-graph/post.md Selmer template."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.logseq :as logseq-util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.net URLEncoder)))

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

(defn- page-url
  "Url of a non-journal page's post, assuming its post is named after `title`"
  [title]
  (str (str/replace (URLEncoder/encode (str title) "UTF-8") "+" "%20") ".html"))

(defn- journal-date
  "Converts a journal day e.g. 20260510 to its YYYY-MM-DD date"
  [day]
  (let [s (str day)]
    (str (subs s 0 4) "-" (subs s 4 6) "-" (subs s 6 8))))

(defn- ident-link
  "Links a :schema or :tagaholic class or property `ident` to its url in its
  index.edn. :schema links are external. Returns nil for other idents"
  [indexes ident]
  (when-let [[_ site kind] (some->> (namespace ident)
                                    (re-matches #"(schema|tagaholic)\..*(class|property)"))]
    {:title (name ident)
     :external? (= "schema" site)
     :url (or (get-in indexes [site (if (= "class" kind) :classes :properties)
                               (keyword (name ident)) :build/properties :url])
              (cli-util/error "No" (str site) "index.edn url found for" ident))}))

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
  "Node for an export page with its title and the url of its post. Journals
  without a published post have no url"
  [ctx {:build/keys [journal] :block/keys [title]}]
  (if journal
    {:title (get-in ctx [:journal-titles journal] (journal-date journal))
     :url (when (contains? (:published-days ctx) journal)
            (str (journal-date journal) ".html"))}
    {:title title :url (page-url title)}))

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
                   [uuid (or (ident-link (:indexes ctx) ident)
                             {:title title :url (page-url title)})]))
               (merge (:classes export) (:properties export))))))

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
      {:title ref :url (page-url ref)})))

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
                :url url
                :external? true})
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
             (tags-html ctx (:build/tags block))
             (title-html ctx block)
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

(defn- select-journals
  "Selects `journals` whose dates are in `args`, or all `journals` when there
  are no `args`. Errors on dates that aren't journals"
  [journals args]
  (if (empty? args)
    journals
    (let [by-date (into {} (map (juxt (comp journal-date :build/journal :page) identity) journals))]
      (when-let [unknown (seq (remove by-date args))]
        (cli-util/error "These are not journal dates in the graph:" (pr-str (vec (sort unknown)))))
      (map by-date (distinct args)))))

(defn- journal-post [ctx {:keys [page blocks]}]
  (let [day (:build/journal page)
        date (journal-date day)
        stats (atom {:blocks 0 :tasks 0})
        body (blocks-html (assoc ctx :stats stats) blocks)]
    (merge {:date date
            :file (str date ".md")
            :title (or (get-in ctx [:journal-titles day])
                       (cli-util/error "No title found for journal" date))
            :publish? (publishable? blocks)
            :entities (count (remove #(or (task? %) (empty-block? %)) blocks))
            :body body}
           @stats)))

(defn- journal-posts
  "Reads `graph` and returns a post for each of its journals selected by `args`.
  Only posts with :publish? are written"
  [{:keys [graph fresh args]}]
  (let [export (export-graph graph fresh)
        journals (sort-by (comp :build/journal :page)
                          (filter (comp :build/journal :page) (:pages-and-blocks export)))
        titles (journal-titles graph)
        ctx {:indexes {"schema" (read-resource-edn "schema-org-index.edn")
                       "tagaholic" (read-resource-edn "tagaholic-me-index.edn")}
             :journal-titles titles
             :journal-days (zipmap (vals titles) (keys titles))
             ;; From all journals so links between journals work when only some are selected
             :published-days (set (keep #(when (publishable? (:blocks %)) (get-in % [:page :build/journal]))
                                        journals))}
        ctx (assoc ctx :nodes (graph-nodes ctx export))]
    (mapv #(journal-post ctx %) (select-journals journals args))))

(defn- site-dir [dir]
  (let [dir (fs/expand-home dir)]
    (when-not (fs/directory? dir) (cli-util/error "Directory" (str dir) "does not exist"))
    dir))

(defn- post-path [dir post]
  (fs/path dir "posts" (:file post)))

(defn- write-posts
  "Writes `posts` with `bb quickblog new`, or only lists them when `pretend`,
  and reports created pages and blocks and tasks hidden from them and from
  `unpublished` journals"
  [posts unpublished dir pretend]
  (let [template-file (str (io/file (io/resource post-template)))]
    (doseq [{:keys [file title date body entities] :as post} posts]
      (if pretend
        (println "Would create" (str (post-path dir post)))
        (shell {:dir (str dir)} "bb" "quickblog" "new" "--file" file "--title" title
               "--date" date "--tags" "journal" "--template-file" template-file
               "--entities" (str entities) "--body" body)))
    (println (if pretend "Would create" "Created") (count posts) "pages with"
             (reduce + (map :blocks posts)) "blocks in" (str (fs/path dir "posts")))
    (println "Hid" (reduce + (map :tasks (concat posts unpublished))) "tasks")))

(defn- create [{:keys [dir pretend] :as opts}]
  (let [dir (site-dir dir)
        {publish true unpublished false} (group-by :publish? (journal-posts opts))
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
        posts (journal-posts opts)
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
          :desc "Journal dates as YYYY-MM-DD. Defaults to all journals"}})

(def ^:private table
  [{:cmds ["create"]
    :fn (fn [{:keys [opts]}] (create opts))
    :spec common-spec
    :args->opts (repeat :args)
    :doc "Create a quickblog post for each journal, or only the given ones, that
  has a top-level block that isn't a task. Existing posts are left unchanged."}
   {:cmds ["rebuild"]
    :fn (fn [{:keys [opts]}] (rebuild opts))
    :spec common-spec
    :args->opts (repeat :args)
    :doc "Recreate posts for all or the given journals and render the site from
  scratch: runs `bb quickblog clean`, deletes the journals' posts and watch's
  cache, creates them and runs `bb quickblog render`. Posts that aren't for the
  graph's journals are left alone. Stop `bb quickblog watch` first."}])

(defn -main [& args]
  (cli/dispatch table args {:prog "logseq-publish-graph" :help true}))
