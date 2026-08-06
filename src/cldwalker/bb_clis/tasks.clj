(ns cldwalker.bb-clis.tasks
  "Contains tasks which only depend on built-in namespaces or local ones in src/"
  (:require [babashka.tasks :refer [run]]
            [babashka.cli :as cli]
            [babashka.process :refer [shell]]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.data :as data]
            [clojure.string :as str]
            [cldwalker.bb-clis.cli :as cli-util]))

(defn brew-search-info
  "Runs a brew info on all brew search results."
  {:org.babashka/cli {:spec {:search {:desc "Search terms" :coerce [] :positional true}}
                      :args->opts (repeat :search)
                      :require [:search]}}
  [{:keys [search]}]
  (let [results (-> (shell {:out :string}
                           (str/join " " (into ["brew" "search"]
                                               search)))
                    :out
                    str/split-lines)
        brew-packages (remove (fn [x] (re-find (re-pattern "(Casks|Formulae)$") x)) results)]
    (shell (str "brew info " (str/join " " brew-packages)))))

(defn json=
  "Check equality of given json files.
  Useful when diff fails you due to random sort of json files produced differently."
  {:org.babashka/cli {:spec {:file1 {:desc "First json file" :positional true}
                             :file2 {:desc "Second json file" :positional true}
                             :files {:desc "Additional json files" :coerce [] :positional true}}
                      :args->opts (list* :file1 :file2 (repeat :files))
                      :require [:file1 :file2]}}
  [{:keys [file1 file2 files]}]
  (prn (apply = (map (fn [x] (-> x slurp json/parse-string)) (into [file1 file2] files)))))

(defn edn=
  "Check equality of given edn files.
  Useful when diff fails you due to random sort of edn files produced differently."
  {:org.babashka/cli {:spec {:file1 {:desc "First edn file" :positional true}
                             :file2 {:desc "Second edn file" :positional true}
                             :files {:desc "Additional edn files" :coerce [] :positional true}}
                      :args->opts (list* :file1 :file2 (repeat :files))
                      :require [:file1 :file2]}}
  [{:keys [file1 file2 files]}]
  (prn (apply = (map (fn [x] (-> x slurp edn/read-string)) (into [file1 file2] files)))))

(defn data-diff
  "Runs data/diff on two edn files."
  {:org.babashka/cli {:spec {:file1 {:desc "First edn file" :positional true}
                             :file2 {:desc "Second edn file" :positional true}}
                      :args->opts [:file1 :file2]
                      :require [:file1 :file2]}}
  [{:keys [file1 file2]}]
  (prn (apply data/diff (map (fn [x] (-> x slurp edn/read-string)) [file1 file2]))))

(def ^:api every-dir-shell-cli
  "babashka.cli options for [[every-dir-shell]]. Referenced as `:cli` in bb.edn."
  {:spec {:directory {:alias :d
                      :ref "<dir>"
                      :coerce []
                      :desc "Directory to run command in. Defaults to $PWD"}
          :cmd {:desc "Shell command" :positional true}}
   :args->opts (repeat :cmd)
   :coerce {:cmd []}
   :repeated-opts true})

(defn every-dir-shell
  "Run shell command on every dir."
  [_options]
  ;; Options are parsed from raw args with split-leading-opts as the trailing
  ;; shell command can have options of its own e.g. `ls -la`
  (let [option-spec (dissoc (:spec every-dir-shell-cli) :cmd)
        [option-args cmd-args] (cli-util/split-leading-opts option-spec *command-line-args*)
        options (:opts (cli/parse-args option-args
                                       {:spec option-spec
                                        :validate {:directory fs/directory?}}))
        directories (or (:directory options) [(System/getenv "PWD")])
        cmd (str/join " " cmd-args)]
    (doseq [dir directories]
      (println "=== Directory -" dir "===")
      (shell {:dir dir} cmd)
      (println ""))))

(defn repl
  [args]
  (let [task (symbol (first args))]
    (binding [*command-line-args* (rest args)]
      ;; Assumes task stdout is edn
      #_:clj-kondo/ignore
      (def ^:private result (edn/read-string (with-out-str (run task)))))
    ;; Used to use clojure.main/repl but this allows for in-editor repl
    ((requiring-resolve 'clojure.core.server/start-server)
     {:port 5555
      :name "bb-task"
      :accept 'clojure.core.server/repl})
    ((requiring-resolve 'clojure.core.server/repl))))

(defn do-sh
  "Runs shell command for each element on stdin seq."
  {:org.babashka/cli {:spec {:cmd {:desc "Shell command" :positional true}}
                      :args->opts (repeat :cmd)
                      :coerce {:cmd []}}}
  [_options]
  ;; Uses raw args since the shell command can have options of its own
  (run! #(apply shell (concat *command-line-args* [%])) (edn/read *in*)))

(defn wc-l
  "Filter files by max loc."
  {:org.babashka/cli {:spec {:max-loc {:desc "Max lines of code" :coerce :long :positional true}
                             :files {:desc "Files to filter" :coerce [] :positional true}}
                      :args->opts (cons :max-loc (repeat :files))
                      :require [:max-loc]}}
  [{:keys [max-loc files]}]
  (->> (apply shell {:out :string} "wc -l" files)
       :out
       str/split-lines
       butlast
       (map #(let [[_ loc file] (str/split % #"\s+" 4)]
               {:loc (Integer/parseInt loc) :file file}))
       (sort-by :loc)
       (filter #(<= (:loc %) max-loc))
       (map :file)
       prn))

(defn- complete-test-nses
  "Completion candidates for the test task's `--nses` - namespaces found in the
  `--dirs` values. Relative dirs resolve against this repo so completion works
  from any directory. tools.namespace comes from the test task's :extra-deps
  and is only required when completing."
  [{:keys [opts]}]
  (let [find-nses (requiring-resolve 'clojure.tools.namespace.find/find-namespaces-in-dir)
        root (fs/parent (fs/absolutize (System/getProperty "babashka.config")))]
    (->> (or (seq (:dirs opts)) ["test"])
         (map #(if (fs/relative? %) (fs/path root %) (fs/path %)))
         (filter fs/directory?)
         (mapcat #(find-nses (fs/file (str %))))
         sort
         (map str))))

(def test-cli
  "babashka.cli options for the test task. Referenced as `:cli` in bb.edn."
  {:spec {:dirs {:desc "Directories containing tests" :coerce [] :default ["test"]}
          :nses {:desc "Namespace symbols to test" :coerce [:symbol]
                 :complete-fn complete-test-nses :alias :n}
          :patterns {:desc "Regex strings to match namespaces" :coerce [] :alias :p}
          :vars {:desc "Fully qualified symbols of test vars to run" :coerce [:symbol]}
          :includes {:desc "Test metadata keywords to include" :coerce [:keyword] :alias :i}
          :excludes {:desc "Test metadata keywords to exclude" :coerce [:keyword] :alias :e}}})

(def http-server-cli
  "babashka.cli options for the http-server task. Referenced as `:cli` in bb.edn."
  {:spec {:port {:coerce :long :desc "Port to serve on" :default 8090}
          :dir {:desc "Directory from which to serve assets" :default "."}
          :headers {:coerce :edn :desc "Map of headers"}}})

(defn grep-result-frequencies
  "Takes piped in grep output and prints out frequency by file counts."
  [_options]
  (let [results (->> *in*
                     slurp
                     str/split-lines
                     (map #(second (re-find #"(\S+):" %)))
                     frequencies
                     (sort-by second >))]
    (doseq [r results]
      (apply println r))))

(comment
 (-> result)
 )
