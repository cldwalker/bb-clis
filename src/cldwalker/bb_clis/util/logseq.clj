(ns cldwalker.bb-clis.util.logseq
  "Logseq utility functions."
  (:require [babashka.process :refer [shell]]
            [clojure.edn :as edn]))

(defn graphs
  "Returns a vector of graph names from `logseq graph list -o edn`."
  []
  (try
    (let [{:keys [out]} (shell {:out :string :continue true} "logseq" "graph" "list" "-o" "edn")
          {:keys [status data]} (edn/read-string out)]
      (when (= :ok status)
        (:graphs data)))
    (catch Exception _ nil)))

(defn complete-graphs
  "Completion candidate fn for babashka.cli `:complete-fn` on `:graph` options."
  [_]
  (graphs))
