(ns bin.bb-replace-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.java.shell :as shell]))

(deftest help-option
  (let [cmd-results (shell/sh "bb-replace" "-h"
                              :dir "test/resources/bin/bb_replace_test")
        expected-results (-> (io/resource "bin/bb_replace_test/help-option.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))))

(deftest standard-replacement
  (spit (io/resource "bin/bb_replace_test/project.clj") "(defproject bar \"1.0.0\")")
  (let [cmd-results (shell/sh "bb-replace" "lein-version" "2.0.0"
                              :dir "test/resources/bin/bb_replace_test")
        expected-results (-> (io/resource "bin/bb_replace_test/standard-replacement.edn")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))
    (is (str/includes? (slurp (io/resource "bin/bb_replace_test/project.clj")) "2.0.0"))))
