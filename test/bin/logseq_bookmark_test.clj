(ns bin.logseq-bookmark-test
  (:require [clojure.test :refer [deftest is testing]]
            [cldwalker.bb-clis.bin.logseq-bookmark :as bookmark]))

(deftest parse-title
  (testing "entities are decoded and whitespace, including non-breaking spaces, is normalized"
    (is (= "Homelessness Strategic Framework | Durham, NC"
           (#'bookmark/parse-title
            "<html><head><title>\n  Homelessness&#160;Strategic Framework&#xA0;|&nbsp; Durham, NC </title></head></html>"))))
  (testing "named entities are decoded"
    (is (= "Tom & Jerry's <Show> — “Live”"
           (#'bookmark/parse-title
            "<title>Tom &amp; Jerry&#39;s &lt;Show&gt; &mdash; &ldquo;Live&rdquo;</title>"))))
  (testing "nil when there is no title"
    (is (nil? (#'bookmark/parse-title "<p>no title</p>")))
    (is (nil? (#'bookmark/parse-title "")))))
