(ns cldwalker.bb-clis.bin.bb-project-clj
  "Prints out a lein project.clj file as a map"
  (:require [babashka.cli :as cli]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

;; While these are preventing breakages, these may be worth putting
;; behind an option if they have unintended consequences
(defn- modify-project-clj [string]
  (-> string
      ;; Ignore eval forms that break read-string
      (str/replace "#=" "")
      ;; Turn regexs into strings as regexs aren't supported by read-string
      (str/replace "#\"" "\"")))

(defn- extract-project-form
  "Extracts defproject form from given project.clj string. drop-forms is the
  number of forms to drop before getting to defproject"
  [drop-forms string]
  (->> (str "[" string "]")
       read-string
       (drop drop-forms)
       first))

;; Modified from https://gist.github.com/swlkr/3f346c66410e5c60c59530c4413a248e#gistcomment-3232605
(defn- project-clj-map [filename drop-forms]
  (->> (slurp filename)
       modify-project-clj
       (extract-project-form drop-forms)
       (drop 1)
       (partition 2)
       (map vec)
       (into {})))

(defn- command [{:keys [opts]}]
  (let [project-clj (project-clj-map (:file opts) (:drop-forms opts))]
    (pprint/pprint project-clj)))

(def ^:private spec
  {:file {:alias :f :default "project.clj" :desc "Location of project.clj file"}
   :drop-forms {:alias :d :coerce :long :default 0 :desc "Forms to drop before defproject form"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec}]
                args
                {:prog "bb-project-clj" :help true}))
