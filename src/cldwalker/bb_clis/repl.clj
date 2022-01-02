(ns cldwalker.bb-clis.repl
  "Fns that are useful in a bb repl"
  (:require [babashka.deps :as deps]))

(defn add-portal
  []
  (deps/add-deps '{:deps {djblue/portal {:mvn/version "RELEASE"}}})
  (require '[portal.api :as p])
  (let [p ((requiring-resolve 'portal.api/open))]
    (add-tap (requiring-resolve 'portal.api/submit))
    p))
