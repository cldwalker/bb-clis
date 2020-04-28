(ns cldwalker.babashka.util.associative)

(defn map-keys
  "Maps function `f` over the keys of map `m` to produce a new map."
  [f m]
  (reduce-kv
   (fn [m_ k v]
     (assoc m_ (f k) v)) {} m))

(defn map-vals
  "Maps function `f` over the vals of map `m` to produce a new map."
  [f m]
  (reduce-kv
   (fn [m_ k v]
     (assoc m_ k (f v))) {} m))
