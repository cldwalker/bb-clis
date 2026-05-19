(ns cldwalker.bb-clis.tasks.dev
  "Dev tasks for the bb-clis repo."
  (:require [babashka.tasks :refer [shell]]
            [clojure.string :as str]))

(defn bump-git-sha
  "For each given file, bumps `:git/sha` in `deps/add-deps` to the latest HEAD
   sha in the bb-clis repo."
  [files]
  (let [sha (-> (shell {:out :string} "git" "rev-parse" "HEAD")
                :out
                str/trim)]
    (doseq [file files]
      (let [content (slurp file)
            new-content (str/replace content
                                     #"(:git/sha \")[0-9a-f]+"
                                     (str "$1" sha))]
        (if (= content new-content)
          (println "No :git/sha to bump in" file)
          (do (spit file new-content)
              (println "Bumped" file "to" sha)))))))
