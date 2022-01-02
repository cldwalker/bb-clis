(ns cldwalker.bb-clis.tasks.rdf-data
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [cheshire.core :as json]))

;; TODO: Reuse with bb-logseq-convert
(defn- process-by-timeout
  "Runs cmd which returns output as string. If timeout ms is reached, returns
  what has been written to stdout"
  [cmd timeout]
  (let [out-str (java.io.StringWriter.)
        ret (deref (:out (process/process cmd {:out out-str})) timeout :timeout)]
    (if (= ret :timeout)
      (str out-str)
      (str ret))))

(def rdf-data-cli-options
  [["-a" "--all" "Prints all data, not just schema.org predicates with graph and subject filtered out"]])

(defn rdf-data
  [args options]
  (let [rdf-data (process-by-timeout ["rdf-dereference" (first args)] 2500)
        filter-fn (if (:all options)
                    (constantly true)
                    (fn [{:keys [predicate]}]
                      (or (str/includes? predicate "schema.org")
                          ;; Next two are useful on github
                          (str/includes? predicate "ogp.me")
                          (= "http://www.w3.org/1999/02/22-rdf-syntax-ns#type" predicate))))
        map-fn (if (:all options) identity (fn [m] (dissoc m :subject :graph)))
        result (map map-fn
                    (filter filter-fn (json/parse-string rdf-data true)))]
    result))
