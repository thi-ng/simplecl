(ns simplecl.utils
  "Misc utility functions used by other simplecl namespaces."
  ^{:author "Karsten Schmidt"}
  (:import
    [java.io File])
  (:require
    [clojure.java.io :as io]))

(defn args->array
  "Looks up keyword `args` in `coll`, filters out any nil values and converts
  the remaining items into an array. The `pred` arg in the 3-arg version
  is a predicate applied to each item in `args`. If the predicate returns true
  the item will be accepted without any lookup."
  ([coll args]
    (into-array
      (filter (complement nil?) (map #(% coll) args))))
  ([pred coll args]
    (into-array
      (filter (complement nil?) (map #(if (pred %) % (% coll)) args)))))

(defn ceil-multiple-of
  "Rounds up `b` to a multiple of `a`. Used as helper fn to compute a
  kernel's global workgroup size."
  [a b]
  (let [r (rem b a)] (if (zero? r) b (- (+ b a) r))))

(defn ^java.io.InputStream resource-stream
  "Returns a `java.io.InputStream` for the given resource path."
  [name]
  (-> name io/resource io/input-stream))

(defn ^File temp-file
  ([suffix] (temp-file "simplecl" suffix))
  ([prefix suffix]
  (try
    (File/createTempFile prefix suffix)
    (catch Exception e))))

(defn delete-file
  [f]
  (io/delete-file f true))
