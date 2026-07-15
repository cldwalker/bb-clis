(ns cldwalker.bb-clis.bin.bb-replace
  "Light-weight alternative to sed or perl -i. Aims to provide replacements
  that are more readable by supporting named replacements.
  See get-replacements for config docs"
  (:require [babashka.cli :as cli]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(defn- get-replacements
  "Reads replacements from ./bb-replace.edn and ~/.bb-replace.edn.
A replacement consists of three keys:
* :regex : re-pattern regex to match target substring using clojure.string/replace-first
* :format-string - clojure.core/format generates the replacement string using this format and
  commandline arguments
* :file - File on which to do replacement"
  []
  (cond->> []
           (.exists (io/file (System/getenv "HOME") ".bb-replace.edn"))
           (into
            [(edn/read-string (slurp (io/file (System/getenv "HOME") ".bb-replace.edn")))])

           (.exists (io/file ".bb-replace.edn"))
           (into [(edn/read-string (slurp ".bb-replace.edn"))])

           true (mapv (fn [m] (update-vals m #(update % :regex re-pattern))))
           true (apply merge)))

(defn- update-file-with-replacement
  [{:keys [regex file format-string]} arguments]
  (let [body (slurp file)
        new-body (str/replace-first body regex
                                    (apply format format-string arguments))]
    (if (= body new-body)
      (cli-util/error (str "Replacement did not change " file))
      (spit file new-body))))

(defn- arg->replacement [arg options]
  (if-let [replacement (get (get-replacements) (keyword arg))]
    replacement
    (if-let [file (:file options)]
      {:regex (re-pattern arg)
       ;; Not sure if this is a sensible default
       :format-string "$1 %s"
       :file file}
      (cli-util/error "File option required if providing a regex as a replacement"))))

(defn- command [{:keys [opts args]}]
  (let [replacement (arg->replacement (:replacement opts) opts)]
    (update-file-with-replacement (merge replacement
                                         (select-keys opts [:file :format-string]))
                                  args)))

(def ^:private spec
  ;; :coerce :string prevents a numeric regex like "404" auto-coercing to a number
  {:replacement {:positional true :coerce :string :require true
                 :desc (str "Named replacement or regex. Replacements available: "
                            (str/join ", " (->> (get-replacements) keys (map name))))}
   :file {:alias :f :desc "Overrides default file for a replacement"}
   :format-string {:alias :F :desc "Overrides default format string for a replacement"}})

(defn -main [& args]
  (cli/dispatch [{:cmds [] :fn command :spec spec :args->opts [:replacement]}]
                args
                {:prog "bb-replace" :help true}))
