(ns bin.bb-table-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :as shell]))

(def expected-help
  "Usage: bb-table [OPTIONS]
Reads from stdin if no arguments given
Options:
  -h, --help
  -f, --file FILE
  -c, --columns-filter FILTER          Select columns by comma delimited substring matches or numbers from -H
  -H, --print-headers                  Print column headers and their counts across rows
  -n, --number-rows
  -s, --sort COLUMN                    Sort by column
  -r, --reverse-sort COLUMN            Reverse sort by column
  -S, --style STYLE            :plain  Table style when using table clojar. Available styles are :plain, :org, :unicode and :github-markdown. Default is :plain.
")

(deftest help-option-prints-correctly
  (let [cmd-results (shell/sh "bin/bb-table" "-h"
                              :env {"BABASHKA_CLASSPATH" "src"})]
    (is (zero? (:exit cmd-results)))
    (is (= expected-help (:out cmd-results)))))
