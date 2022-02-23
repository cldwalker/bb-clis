(ns cldwalker.bb-clis.tasks
  (:require [babashka.tasks :refer [shell run]]
            [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn brew-search-info
  [args]
  (let [results (-> (shell {:out :string}
                           (str/join " " (into ["brew" "search"]
                                               args)))
                    :out
                    str/split-lines)
        brew-packages (remove (fn [x] (re-find (re-pattern "(Casks|Formulae)$") x)) results)]
    (shell (str "brew info " (str/join " " brew-packages)))))

(defn json=
  "Useful when diff fails you due to random sort of json files produced differently"
  [args]
  (apply = (map (fn [x] (-> x slurp json/parse-string)) args)))

(defn edn=
  "Useful when diff fails you due to random sort of edn files produced differently"
  [& args]
  (apply = (map (fn [x] (-> x slurp edn/read-string)) args)))

(def every-dir-shell-cli-options
  [["-d" "--directory DIR" "Directories"
    :id :directories
    :default-fn (fn [_x] [(System/getenv "PWD")])
    :validate [fs/directory? "Must be a directory"]
    :multi true
    :update-fn conj]])

(defn every-dir-shell
  [parsed-args]
  (let [{:keys [options arguments]} parsed-args
        args (str/join " " arguments)]
    (doseq [dir (:directories options)]
      (println "=== Directory -" dir "===")
      (shell {:dir dir} args)
      (println ""))))

(defn help
  [args]
   ;; Would rather not dip into explicit env to determine tasks
  (let [tasks (-> (or (System/getenv "BB_EDN") "bb.edn")
                         slurp
                         edn/read-string
                         :tasks)
               task (first args)]
           (if-let [task-map (get tasks (symbol task))]
             (println (format "%s\n\nUsage: bb %s%s"
                              (:doc task-map)
                              task
                              (if-let [usage (:usage task-map)]
                                (str " " usage)
                                "")))
             (do
               (println "Error: No such task exists")
               (System/exit 1)))))

(defn repl
  [args]
  (let [task (symbol (first args))]
    (binding [*command-line-args* (rest args)]
      ;; Assumes task stdout is edn
      #_:clj-kondo/ignore
      (def result (edn/read-string (with-out-str (run task)))))
    ;; Used to use clojure.main/repl but this allows for in-editor repl
    ((requiring-resolve 'clojure.core.server/start-server)
     {:port 5555
      :name "bb-task"
      :accept 'clojure.core.server/repl})
    ((requiring-resolve 'clojure.core.server/repl))))

(defn do-sh
  "Runs shell command for each element on stdin seq"
  [& args]
  (run! #(apply shell (concat args [%])) (edn/read *in*)))

(defn wc-l
  "Filter files by max loc"
  [max-loc & args]
  (let [max-loc_ (Integer/parseInt max-loc)]
    (->> (apply shell {:out :string} "wc -l" args)
         :out
         str/split-lines
         butlast
         (map #(let [[_ loc file] (str/split % #"\s+" 4)]
                 {:loc (Integer/parseInt loc) :file file}))
         (sort-by :loc)
         (filter #(<= (:loc %) max-loc_))
         (map :file)
         prn)))

(defn grep-result-frequencies
  "Takes piped in grep output and prints out frequency by file counts"
  []
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
