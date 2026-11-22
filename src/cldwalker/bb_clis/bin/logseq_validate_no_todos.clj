(ns cldwalker.bb-clis.bin.logseq-validate-no-todos
  "Fail if blocks contain 'td:' outside of incomplete tasks or blocks that
  reference them. A task is a block with a status or tagged with a class that
  has a status property e.g. Task or Issue. The nearest ancestor that is, or
  references, a task decides."
  (:require [babashka.cli :as cli]
            [babashka.process :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.logseq :as logseq-util]
            [clojure.edn :as edn]))

(defn- graph-args [graph]
  (when graph ["-g" graph]))

(defn- logseq-query [graph query inputs]
  (let [{:keys [out]} (apply shell {:out :string} "logseq" "query"
                             (concat (graph-args graph)
                                     ["-o" "edn" "--query" (pr-str query)
                                      "--inputs" (pr-str inputs)]))
        {:keys [status data]} (edn/read-string out)]
    (when (not= :ok status)
      (cli-util/error "Query failed:" out))
    (:result data)))

(def ^:private rules
  '[[(ancestor ?a ?b) [?b :block/parent ?a]]
    [(ancestor ?a ?b) [?b :block/parent ?x] (ancestor ?a ?x)]
    ;; Class has status as a class property, directly or by extends
    [(status-class ?c)
     [?c :logseq.property.class/properties ?p]
     [?p :db/ident :logseq.property/status]]
    [(status-class ?c) [?c :logseq.property.class/extends ?e] (status-class ?e)]
    [(task ?t) [?t :logseq.property/status _]]
    [(task ?t) [?t :block/tags ?c] (status-class ?c)]
    ;; A task without a status is incomplete
    [(incomplete-task ?t)
     (task ?t)
     (not-join [?t]
               [?t :logseq.property/status ?s]
               (or [?s :db/ident :logseq.property/status.done]
                   [?s :db/ident :logseq.property/status.canceled]))]
    ;; Block is a task or references one
    [(task-context ?a) (task ?a)]
    [(task-context ?a) [?a :block/refs ?t] (task ?t)]
    [(incomplete-context ?a) (incomplete-task ?a)]
    [(incomplete-context ?a) [?a :block/refs ?t] (incomplete-task ?t)]])

(def ^:private invalid-blocks-query
  "Finds 'td:' blocks whose nearest task-context ancestor is missing or has no
  incomplete task"
  '[:find ?b ?title
    :in $ %
    :where
    [?b :block/title ?title]
    (or [(clojure.string/includes? ?title "td:")]
        [(= ?title "td")])
    (not-join [?b]
              (ancestor ?a ?b)
              (incomplete-context ?a)
              ;; No nearer task context between ?a and ?b
              (not-join [?a ?b]
                        (ancestor ?c ?b)
                        (ancestor ?a ?c)
                        (task-context ?c)))])

(defn- command [{:keys [opts]}]
  (let [rows (->> (logseq-query (:graph opts) invalid-blocks-query [rules])
                  (map (fn [[id title]] {:id id :title title}))
                  (sort-by :id))]
    (if (empty? rows)
      (println "No invalid 'td:' or 'td' blocks found.")
      (do
        (apply shell "logseq" "show" (concat (graph-args (:graph opts)) ["--id" (pr-str (mapv :id rows))]))
        (println (count rows) "invalid block(s):" (pr-str (mapv :id rows)))
        (System/exit 1)))))

(def ^:private spec
  {:graph {:alias :g
           :desc "Graph name"
           :complete-fn logseq-util/complete-graphs}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec}]
                args
                {:prog "logseq-validate-no-todos" :help true}))
