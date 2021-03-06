(ns test-runner
  (:require [clojure.test :as t]
            [bin.bb-table-test]
            [bin.bb-replace-test]))

(defn -main []
  (let [{:keys [:fail :error]} (t/run-all-tests #".*-test")]
    (System/exit (+ fail error))))
