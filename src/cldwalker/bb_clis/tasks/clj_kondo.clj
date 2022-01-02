(ns cldwalker.bb-clis.tasks.clj-kondo
  (:require [babashka.pods :as pods]))

;; Need at least "2021.10.19" for var-meta
(pods/load-pod "clj-kondo")
(require '[pod.borkdude.clj-kondo :as clj-kondo])

(defn var-sizes
  [args]
  (let [paths (or args ["src"])
        {{:keys [var-definitions var-usages]} :analysis}
        (clj-kondo/run!
         {:lint paths
          :config {:output {:analysis true}}})
        comment-blocks (->> var-usages
                            (filter (fn [m] (= (:name m) 'comment)))
                            (map (fn [m] (select-keys m [:row :end-row :filename]))))
        in-comment-block? (fn [filename line]
                            (some (fn [m]
                                    (and (= filename (:filename m)) (<= (:row m) line (:end-row m))))
                                  comment-blocks))
        vars (->> var-definitions
                  (keep (fn [m]
                          (when-not (in-comment-block? (:filename m) (:row m))
                            {:name (:name m)
                             :loc-size (inc (- (:end-row m) (:row m)))
                             :filename (:filename m)})))
                  (sort-by :loc-size (fn [x y] (compare y x)))
                  (take 10))]
    vars))

(defn var-meta
  [args]
  (let [paths (or (seq args) ["src"])
        {{:keys [var-definitions]} :analysis}
        (clj-kondo/run!
         {:lint paths
          :config {:output {:analysis {:var-definitions {:meta true}}}}})
        matches (keep (fn [m]
                        (when (:meta m)
                          {:var (str (:ns m) "/" (:name m))
                           :meta (:meta m)}))
                      var-definitions)]
    matches))
