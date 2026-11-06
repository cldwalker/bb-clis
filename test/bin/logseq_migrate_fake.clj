(ns bin.logseq-migrate-fake
  "Fake Logseq graph for exercising a migrate command end to end without the
  logseq CLI. Records each import and shell command instead of running them"
  (:require [babashka.process :as process]
            [cldwalker.bb-clis.cli :as cli-util]
            [cldwalker.bb-clis.util.logseq :as logseq-util]
            [clojure.string :as str]))

(def person-uuid #uuid "00000000-0000-0000-0000-00000000000a")
(def alt-uuid #uuid "00000000-0000-0000-0000-00000000000b")
(def kid-uuid #uuid "00000000-0000-0000-0000-00000000000c")
(def alias-uuid #uuid "00000000-0000-0000-0000-00000000000d")
(def new-person-uuid #uuid "00000000-0000-0000-0000-00000000000e")
(def page-uuid #uuid "00000000-0000-0000-0000-000000000020")
(def block-uuid #uuid "00000000-0000-0000-0000-000000000021")

(def page-entity {:db/id 20 :block/uuid page-uuid :block/title "P"})
(def block-entity {:db/id 21 :block/uuid block-uuid :block/title "b" :block/page {:block/uuid page-uuid}})

(defn- has? [q x] (some #{x} (tree-seq coll? seq q)))

(defn- fake-query
  "Answers the queries migrate makes from `state`, a map of:
  * `:classes`/`:properties` - user class/property rows
  * `:tagged`/`:valued` - class/property ident -> associated entities
  * `:extends` - extending-class rows, `:aliases` - [ident alias-uuid] rows
  * `:db-ids` - original ident -> db id, `:remaining` - association count"
  [state]
  (fn [_graph q inputs]
    (let [input-set (set (first inputs))
          [_ attr v] (last q)]
      (cond
        (has? q 'count) (:remaining state 0)
        (has? q :logseq.property.class/extends) (:extends state)
        (has? q :block/alias) (filter (comp input-set first) (:aliases state))
        (has? q :logseq.class/Tag) (:classes state)
        (has? q :logseq.class/Property) (:properties state)
        (has? q '?ident) (for [i (first inputs) :when (get-in state [:db-ids i])]
                           [{:db/id (get-in state [:db-ids i]) :db/ident i}])
        (= :block/tags attr) (map vector (get-in state [:tagged v]))
        :else (map vector (get-in state [:valued attr]))))))

(defn run
  "Calls `f` against a fake graph built from `state` (see [[fake-query]]),
  plus `:existing` idents and `:uuids` for replacement idents. Returns its
  `:out`, `:error` message, `:imports` EDN and `:shells` command args"
  [state f]
  (let [imports (atom [])
        shells (atom [])
        error (atom nil)
        out (with-redefs [logseq-util/logseq-query (fake-query state)
                          logseq-util/existing-idents (fn [_ ks] (set (filter (:existing state) ks)))
                          logseq-util/existing-user-class-titles (fn [_ _] #{})
                          logseq-util/existing-property-titles (fn [_ _] #{})
                          logseq-util/idents->uuids (fn [_ ks] (select-keys (:uuids state) ks))
                          logseq-util/import-edn (fn [_ edn] (swap! imports conj edn))
                          process/shell (fn [_opts & args] (swap! shells conj (vec args)) {:exit 0 :out "" :err ""})
                          cli-util/error (fn [& msgs] (throw (ex-info (str/join " " msgs) {})))]
              (with-out-str
                (try (f)
                     (catch clojure.lang.ExceptionInfo e (reset! error (ex-message e))))))]
    {:out out :error @error :imports @imports :shells @shells}))
