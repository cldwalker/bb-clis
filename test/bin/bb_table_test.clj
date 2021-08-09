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

(deftest reverse-sort-option
  (let [cmd-results (shell/sh "bash" "-c" "echo {:a 1 :b 2} | bb-table -r value")
        expected-results (-> (io/resource "bin/bb_table_test/reverse-sort-option.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))))

(deftest sort-option
  (let [cmd-results (shell/sh "bash" "-c" "echo {:a 1 :c 3 :b 2} | bb-table -s value")
        expected-results (-> (io/resource "bin/bb_table_test/sort-option.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))))

(deftest number-rows-option
  (let [cmd-results (shell/sh "bash" "-c" "echo {:a 1 :b 2} | bb-table -n")
        expected-results (-> (io/resource "bin/bb_table_test/number-rows-option.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))))

(deftest print-headers-option
  (let [cmd-results (shell/sh "bash" "-c" "echo '[{:a 1 :b 1} {:a 2}]' | bb-table -H")
        expected-results (-> (io/resource "bin/bb_table_test/print-headers-option.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    ;; Locally this is fine but fails on CI as something tries to download clj 1.10.933
    #_(is (= (:err expected-results) (:err cmd-results)))))

(deftest columns-option-with-abbreviations
  (let [cmd-results (shell/sh "bash" "-c" "echo '[{:apple 1 :banana 1} {:apple 2 :orange 1}]' | bb-table -c a,o")
        expected-results (-> (io/resource "bin/bb_table_test/columns-option-with-abbreviations.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))))

(deftest columns-option-with-header-numbers
  (let [cmd-results (shell/sh "bash" "-c" "echo '[{:apple 1 :banana 1} {:apple 2 :orange 1}]' | bb-table -c 1,2")
        expected-results (-> (io/resource "bin/bb_table_test/columns-option-with-header-numbers.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))))

(deftest file-option
  (let [cmd-results (shell/sh "bb-table" "-f" "test/resources/bb-table-file-input.edn")
        expected-results (-> (io/resource "bin/bb_table_test/file-option.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))))

(deftest simple-vec-errors
  (let [cmd-results (shell/sh "bash" "-c" "echo '[:a :b]' |bb-table")
        expected-results (-> (io/resource "bin/bb_table_test/simple-vec-errors.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))))
