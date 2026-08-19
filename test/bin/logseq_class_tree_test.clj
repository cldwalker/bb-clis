(ns bin.logseq-class-tree-test
  (:require [clojure.test :refer [deftest is testing]]
            [cldwalker.bb-clis.bin.logseq-class-tree :as class-tree]))

(deftest count-str-with-multi-parent-descendant
  ;; Diamond: Thing -> A, Thing -> B, A -> C, B -> C
  (let [ctx {:counts {:C 1 :Thing 1}
             :children {:Thing [:A :B] :A [:C] :B [:C]}
             :show? #{:Thing :A :B :C}
             :parent-counts true}]
    (testing "a descendant reachable through two parents is counted once"
      (is (= " (1/2)" (#'class-tree/count-str :Thing ctx))))
    (testing "parents with a single path to the descendant are unaffected"
      (is (= " (1*)" (#'class-tree/count-str :A ctx))))))

(deftest count-str-with-extends-cycle
  ;; A -> B -> A must terminate and count each class once
  (let [ctx {:counts {:A 1 :B 1}
             :children {:A [:B] :B [:A]}
             :show? #{:A :B}
             :parent-counts true}]
    (is (= " (1/2)" (#'class-tree/count-str :A ctx)))))
