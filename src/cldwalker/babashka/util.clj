(ns cldwalker.babashka.util
  (:require [clojure.java.shell :as shell]))

(defn open-url
  "Osx specific way to open url in browser"
  [url]
  (shell/sh "open" url))
