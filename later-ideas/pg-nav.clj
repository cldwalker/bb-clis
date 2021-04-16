#!/usr/bin/env bb
;; vim: set filetype=clojure:

(require '[babashka.pods :as pods])
(require '[table.core :as t])
; (require '[clojure.datafy :as d])

(pods/load-pod "pod-babashka-postgresql")
(require '[pod.babashka.postgresql :as pg])
; (require '[next.jdbc :as pg])

;; TODO: Use database/parse-url to be env driven
(def db {:dbtype   "postgresql"
         :host     "localhost"
         :dbname   "bengal_dev"
         :user     "me"
         :password ""
         :port     5432})

(def result (pg/execute! db ["select * from \"SiteTrialRead\" limit 10"]))
(prn :META (meta result))
(prn :META2 (meta (first result)))
#_(t/table result)
; (prn (d/nav result :SiteTrialRead/site_id (:SiteTrialRead/site_id result)))
