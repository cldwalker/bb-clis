(ns cldwalker.bb-clis.bin.logseq-graph-grep
  "Recursively grep a logseq graph directory."
  (:require [cldwalker.bb-clis.cli :as cli]
            [babashka.tasks :refer [shell]]
            [clojure.string :as str]))

(defn- current-graph []
  (-> (shell {:out :string :continue true} "zsh" "-ic" "_logseq_current_graph")
      :out
      str/trim
      not-empty))

(defn- command [{:keys [options arguments summary]}]
  (if (:help options)
    (cli/print-summary " [& GREP-ARGS]" summary)
    (let [graph (or (:graph options)
                    (current-graph)
                    (cli/error "Could not determine current graph"))
          dir (str (System/getenv "HOME") "/logseq/graphs/" graph "/mirror/markdown")
          {:keys [exit]} (apply shell {:continue true :dir dir}
                                "grep" "-r" (concat arguments ["."]))]
      (System/exit exit))))

(def ^:private cli-options
  [["-h" "--help"]
   ["-g" "--graph GRAPH" "Graph to grep (default: current graph)"]])

(defn -main [& args]
  (cli/run-command command args cli-options :in-order true))
