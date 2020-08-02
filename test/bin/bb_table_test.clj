(ns bin.bb-table-test
  "All these tests were run in an environment with table clojar installed"
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]))

(deftest help-option
  (let [cmd-results (shell/sh "bb-table" "-h")
        expected-results (-> (io/resource "bin/bb_table_test/help-option.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))))

(deftest map-stdin-argument
  (let [cmd-results (shell/sh "bash" "-c" "echo {:a 1 :b 2} | bb-table")
        expected-results (-> (io/resource "bin/bb_table_test/map-stdin-argument.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))))

(deftest vec-stdin-argument
  (let [cmd-results (shell/sh "bash" "-c" "echo '[[:a :b] [:d :e]]' | bb-table")
        expected-results (-> (io/resource "bin/bb_table_test/vec-stdin-argument.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))))
