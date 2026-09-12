(ns cldwalker.bb-clis.tasks.clj
  "Tasks for converting between clj/cljs/cljc namespaces and their source files"
  (:require [babashka.fs :as fs]
            [babashka.process :refer [shell]]
            [cldwalker.bb-clis.cli :as cli-util]
            [clojure.string :as str]
            [clojure.tools.namespace.file :as ns-file]
            [clojure.tools.namespace.find :as ns-find]
            [edamame.core :as edamame]))

;; Options for parsing entire source files (not just their ns form), where any
;; reader macro may appear. `true` restores each one's default parse behavior;
;; without it, edamame throws on encountering that syntax.
(def ^:private parse-opts
  {:fn true :regex true :deref true :var true :quote true :read-eval true
   :syntax-quote true :unquote true :unquote-splicing true
   :read-cond :allow :features #{:clj :cljs} :auto-resolve-ns true})

(defn- child-dirs
  [dir]
  (->> (fs/list-dir dir)
       (filter fs/directory?)
       (map str)))

(defn- source-paths
  "Returns the current directory's source paths. `src` and `test` are each
  expanded into their child directories when a `src/clj`/`test/clj`-style
  layout is detected, otherwise the dir itself is used."
  []
  (into (if (fs/exists? "src/clj") (child-dirs "src") ["src"])
        (if (fs/exists? "test/clj") (child-dirs "test") ["test"])))

(defn- source-files-in-dir
  "All clj, cljs and cljc source files under `dir`, recursively"
  [dir]
  (into (set (ns-find/find-sources-in-dir dir ns-find/clj))
        (ns-find/find-sources-in-dir dir ns-find/cljs)))

(defn- ns-file-entries
  "`[ns file]` pairs for every namespace found under `source-paths`."
  [source-paths]
  (for [path source-paths
        :when (fs/directory? path)
        file (source-files-in-dir (fs/file path))
        :let [ns-decl (ns-file/read-file-ns-decl file)]
        :when ns-decl]
    [(second ns-decl) (str file)]))

(defn- def-form?
  [form]
  (and (seq? form)
       (symbol? (first form))
       (str/starts-with? (name (first form)) "def")))

(defn- var-def-line
  "Line number of the top-level def/defn/defmacro/etc form naming `var-sym` in
  `file`, or nil if not found."
  [file var-sym]
  (->> (edamame/parse-string-all (slurp (str file)) parse-opts)
       (some (fn [form]
               (when (and (def-form? form) (= (second form) var-sym))
                 (:row (meta form)))))))

(defn- var-at-line
  "Symbol named by the top-level def/defn/defmacro/etc form spanning `line`
  in `file`, or nil if `line` is nil or no such form is found."
  [file line]
  (when line
    (->> (edamame/parse-string-all (slurp (str file)) parse-opts)
         (some (fn [form]
                 (when (and (def-form? form)
                            (symbol? (second form))
                            (<= (:row (meta form)) line (:end-row (meta form))))
                   (second form)))))))

(defn- parse-file-arg
  "Splits `file-arg` into `[file line]`; `line` is nil without a `:LINE` suffix."
  [file-arg]
  (if-let [[_ file line] (re-matches #"(.+):(\d+)" file-arg)]
    [file (parse-long line)]
    [file-arg nil]))

(def sym-to-file-cli
  {:spec {:ns {:desc "Namespace or ns/var to look up" :coerce :symbol :positional true}
          :print {:alias :p :coerce :boolean :desc "Print the file path instead of opening it"}}
   :args->opts [:ns]
   :require [:ns]})

(defn sym-to-file
  "Given a sym, ns or full var, opens its FILE:[LINE] with `code`.
   Use --print to print the location instead"
  [{:keys [ns print]}]
  (let [ns-part (if-let [n (namespace ns)] (symbol n) ns)
        var-part (when (namespace ns) (symbol (name ns)))
        file (get (into {} (ns-file-entries (source-paths))) ns-part)]
    (cond
      (nil? file) (cli-util/error "No file found for ns" ns-part)
      (nil? var-part) (if print (println file) (shell "code" file))
      :else
      (let [line (var-def-line file var-part)]
        (if (nil? line)
          (cli-util/error "No var" ns "found in" file)
          (let [location (str file ":" line)]
            (if print
              (println location)
              (shell "code" "--goto" location))))))))

(def file-to-sym-cli
  {:spec {:file {:desc "File to look up, optionally suffixed with :LINE" :positional true}
          :print {:alias :p :coerce :boolean :desc "Print the namespace instead of copying it to the clipboard"}}
   :args->opts [:file]
   :require [:file]})

(defn file-to-sym
  "Given a file:[line], copies its sym, ns or full var.
   Use --print to print the sym instead"
  [{:keys [file print]}]
  (let [[file-path line] (parse-file-arg file)
        file->ns (into {}
                       (map (fn [[ns-name f]] [(str (fs/absolutize f)) ns-name]))
                       (ns-file-entries (source-paths)))
        ns-name (get file->ns (str (fs/absolutize file-path)))]
    (cond
      (nil? ns-name) (cli-util/error "No ns found for file" file-path)
      :else
      (let [var-name (var-at-line file-path line)
            sym (if var-name (symbol (str ns-name) (str var-name)) ns-name)]
        (if print
          (println sym)
          (do (shell {:in (str sym)} "pbcopy")
              (println (if var-name "Copied var" "Copied ns"))))))))
