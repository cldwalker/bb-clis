(ns cldwalker.bb-clis.bin.bb-cli-test
  "CLI tests that record successful results and then save them as fixtures for tests"
  (:require [babashka.cli :as cli]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

;; :reload picks up the newer babashka.cli dep
(require '[babashka.cli :as cli] :reload)

(def ^:private default-test-format
    "(deftest %s
  (let [cmd-results (shell/sh %s)
        expected-results (-> (io/resource \"%s\")
                             slurp
                             edn/read-string)]
    (is (= (:exit expected-results) (:exit cmd-results)))
    (is (= (:out expected-results) (:out cmd-results)))
    (is (= (:err expected-results) (:err cmd-results)))))
")

(defn- generate-test [test-name fixture-file cmd-vec]
  (format default-test-format
          test-name
          (str/join " " (mapv #(str "\"" % "\"") cmd-vec))
          fixture-file))

(defn- record-test
  "Records test by running the given command and args and saving its output to a
  fixture file. If command and args is a single argument containing whitespace,
  the string is automatically wrapped in `bash -c`. This is useful for testing
  multiple commands e.g. `cat foo.edn | baz`."
  [cmd-and-args {:keys [test file verbose]}]
  (assert (and test file) "--test and --file are required options")
  (let [fixture-file (-> file
                         (str/replace-first #"^test/"  "test/resources/")
                         (str/replace-first #"\.clj$" (str "/" test ".edn")))
        ;; Wrap in bash if multiple commands
        cmd-and-args_ (if (and (= 1 (count cmd-and-args))
                               (str/includes? (first cmd-and-args) " "))
                        (concat ["bash" "-c"] cmd-and-args)
                        cmd-and-args)
        cmd-output (apply shell/sh cmd-and-args_)]
    (when verbose
      (println "Ran command:" cmd-and-args "\nSTDOUT:")
      (println (:out cmd-output)))
    (when-not (-> fixture-file io/file .getParentFile .exists)
      (io/make-parents fixture-file))
    (pprint/pprint cmd-output (io/writer fixture-file))
    (println "Successfully recorded test!")

    {:fixture-file fixture-file
     :cmd-and-args cmd-and-args_}))

(def ^:private default-ns-format
  "(ns %s
  (:require [clojure.test :refer [deftest is]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]))
")

(defn- record-and-add-test
  "Records output of cmd and args. Then adds a test to the given file and points it to
  the recorded fixture file."
  [args {:keys [test file] :as options}]
  (let [{:keys [fixture-file cmd-and-args]} (record-test args options)
        new-test-string (generate-test test
                                       (str/replace-first fixture-file "test/resources/" "")
                                       cmd-and-args)]
    (when-not (.exists (io/file file))
      (spit file
            (format default-ns-format (-> file
                                          (str/replace #"(^test/|\.clj$)" "")
                                          (str/replace "/" ".")
                                          (str/replace "_" "-"))))
      (println "Created test file!"))
    (spit file (str "\n" new-test-string) :append true)
    (println "Successfully added test!")))

(def ^:private spec
  {:file {:alias :f :desc "Test file to add test to"}
   :verbose {:alias :v :coerce :boolean :desc "Print verbose output"}
   :test {:alias :t :desc "Test name"}})

(defn- record-cmd [{:keys [opts args]}]
  (record-test args opts)
  nil)

(defn- add-cmd [{:keys [opts args]}]
  (record-and-add-test args opts))

(defn- print-help []
  (println (str "Usage: bb-cli-test [options] add|record [& command-and-args]\n\n"
                "Options:\n"
                (cli/format-opts {:spec (assoc spec :help {:alias :h :coerce :boolean :desc "Show this help"})}))))

(defn -main [& args]
  (cond
    (or (empty? args) (some #{"-h" "--help"} args))
    (print-help)

    :else
    (let [[our-args rest-args] (cli-util/split-leading-opts spec args)
          opts (:opts (cli/parse-args our-args {:spec spec}))
          [subcmd & cmd-args] rest-args]
      (case subcmd
        "add" (add-cmd {:opts opts :args cmd-args})
        "record" (record-cmd {:opts opts :args cmd-args})
        (cli-util/error "Unknown subcommand:" (pr-str subcmd))))))
