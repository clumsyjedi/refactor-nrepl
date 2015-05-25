(ns refactor-nrepl.rename-file-or-dir
  (:require [clojure
             [edn :as edn]
             [string :as str]]
            [clojure.java.classpath :as cp]
            [clojure.tools.namespace
             [file :as file :refer [clojure-file?]]
             [find :refer [find-clojure-sources-in-dir]]
             [track :as tracker]]
            [me.raynes.fs :as fs]
            [refactor-nrepl.ns
             [helpers :refer [file-content-sans-ns read-ns-form]]
             [ns-parser :as ns-parser]
             [pprint :refer [pprint-ns]]
             [rebuild :as builder :refer [rebuild-ns-form]]]
            [refactor-nrepl.util :refer [ns-from-string]])
  (:import java.io.File
           java.util.regex.Pattern))

(declare rename-file-or-dir)

(defn- project-clj-files-on-classpath []
  (let [dirs-on-cp (filter fs/directory? (cp/classpath))]
    (mapcat find-clojure-sources-in-dir dirs-on-cp)))

(defn- largest-common-prefix [s1 s2]
  (let [longest (if (> (count s1) (count s2)) s1 s2)
        shortest (if (<= (count s1) (count s2)) s1 s2)
        substrings (into #{}
                         (map #(.substring shortest 0 %)
                              (range 0 (inc (count shortest)))))]
    (reduce #(if (and (.startsWith longest %2) (> (count %2) (count %1))) %2 %1)
            substrings)))

(defn- normalize-to-unix-path [path]
  (if (.contains (System/getProperty "os.name") "Windows")
    (.replaceAll path (Pattern/quote "\\") "/")
    path))

(defn- dirs-on-classpath []
  (->> (cp/classpath) (filter fs/directory?)
       (map #(.toLowerCase (.getAbsolutePath %)))))

(defn- chop-src-dir-prefix
  "Given a path cuts away the part matching a dir on classpath."
  [path]
  (let [chop-prefix (fn [dir]
                      (->> dir
                           normalize-to-unix-path
                           .toLowerCase
                           Pattern/quote
                           re-pattern
                           (str/split path)
                           second))
        shortest (fn [acc val] (if (< (.length acc) (.length val)) acc val))]
    (let [relative-paths (remove nil? (map chop-prefix (dirs-on-classpath)))]
      (if-let [p (cond
                   (= (count relative-paths) 1) (first relative-paths)
                   (> (count relative-paths) 1) (reduce shortest relative-paths))]
        (if (.startsWith p "/")
          (.substring p 1)
          p)
        (IllegalStateException. (str "Can't find src dir prefix for path " path))))))

(defn- path->ns
  "Given an absolute filepath to a non-existant file determine the
  name of the ns."
  [new-path]
  (-> new-path chop-src-dir-prefix fs/path-ns))

(defn- build-tracker []
  (let [tracker (tracker/tracker)]
    (file/add-files tracker (project-clj-files-on-classpath))))

(defn- invert-map
  "Creates a new map by turning the vals into keys and vice versa"
  [m]
  (reduce (fn [m kv] (assoc m (second kv) (first kv))) {} m))

(defn- get-dependents
  "Get the dependent files for ns from tracker."
  [tracker my-ns]
  (let [deps (my-ns (:dependents (:clojure.tools.namespace.track/deps tracker)))
        ns-to-file-map (invert-map (:clojure.tools.namespace.file/filemap (build-tracker)))]
    (map ns-to-file-map deps)))

(defn update-ns-reference-in-libspec
  [old-ns new-ns libspec]
  (if (= (:ns libspec) old-ns)
    (assoc libspec :ns new-ns)
    libspec))

(defn- update-libspecs
  "Replaces any references old-ns with new-ns in all libspecs."
  [libspecs old-ns new-ns]
  (map (partial update-ns-reference-in-libspec old-ns new-ns) libspecs))

(defn- replace-package-prefix
  [old-prefix new-prefix class]
  (if (.startsWith class old-prefix)
    (str/replace class old-prefix new-prefix)
    class))

(defn- update-class-references
  [classes old-ns new-ns]
  (let [old-prefix (str/replace (str old-ns) "-" "_")
        new-prefix (str/replace (str new-ns) "-" "_")]
    (map (partial replace-package-prefix old-prefix new-prefix) classes)))

(defn- create-new-ns-form
  "Reads file and returns an updated ns."
  [file old-ns new-ns]
  (let [ns-form (read-ns-form file)
        libspecs (ns-parser/get-libspecs ns-form)
        classes (ns-parser/get-imports ns-form)
        deps {:require (update-libspecs libspecs old-ns new-ns)
              :import (update-class-references classes old-ns new-ns)}]
    (pprint-ns (rebuild-ns-form ns-form deps))))

(defn- update-file-content-sans-ns
  "Any fully qualified references to old-ns has to be replaced with new-ns."
  [file old-ns new-ns]
  (let [old-prefix (str (str/replace old-ns "-" "_") "/")
        new-prefix (str (str/replace new-ns "-" "_") "/")
        old-ns-ref (str old-ns "/")
        new-ns-ref (str new-ns "/")]
    (-> file
        slurp
        file-content-sans-ns
        (str/replace old-prefix new-prefix)
        (str/replace old-ns-ref new-ns-ref))))

(defn- update-dependent
  "New content for a dependent file."
  [file old-ns new-ns]
  (str (create-new-ns-form file old-ns new-ns)
       "\n"
       (update-file-content-sans-ns file old-ns new-ns)))

(defn- rename-file!
  "Actually rename a file."
  [old-path new-path]
  (fs/mkdirs (fs/parent new-path))
  (fs/rename old-path new-path)
  (loop [dir (.getParentFile (File. old-path))]
    (when (empty? (.listFiles dir))
      (.delete dir)
      (recur (.getParentFile dir)))))

(defn- update-dependents!
  "Actually write new content for dependents"
  [new-dependents]
  (doseq [[f content] new-dependents]
    (spit f content)))

(defn- update-ns!
  "After moving some file to path update its ns to reflect new location."
  [path old-ns]
  (let [new-ns (path->ns path)
        f (File. path)]
    (->> new-ns
         str
         (str/replace-first (slurp f) (str old-ns))
         (spit f))))

(defn- rename-clj-file
  "Move file from old to new, updating any dependents."
  [old-path new-path]
  (let [old-ns (ns-from-string (slurp old-path))
        new-ns (path->ns new-path)
        dependents (get-dependents (build-tracker) old-ns)
        new-dependents (atom {})]
    (doseq [f dependents]
      (swap! new-dependents
             assoc (.getAbsolutePath f) (update-dependent f old-ns new-ns)))
    (rename-file! old-path new-path)
    (update-ns! new-path old-ns)
    (update-dependents! @new-dependents)
    (into '() (map #(.getAbsolutePath %) dependents))))

(defn- merge-paths
  "Update path with new prefix when parent dir is moved"
  [path old-parent new-parent]
  (str/replace path old-parent new-parent))

(defn- rename-dir [old-path new-path]
  (let [old-path (if (.endsWith old-path "/") old-path (str old-path "/"))
        new-path (if (.endsWith new-path "/") new-path (str new-path "/"))]
    (flatten (for [f (file-seq (File. old-path))
                   :when (not (fs/directory? f))
                   :let [path (.getAbsolutePath f)]]
               (rename-file-or-dir path (merge-paths path old-path new-path))))))

(defn rename-file-or-dir
  "Renames a file or dir updating all dependent files.

  Returns a list of all files that were updated."
  [old-path new-path]
  {:pre [(not (str/blank? old-path))
         (not (str/blank? new-path))
         (or (fs/file? old-path) (fs/directory? old-path))]}
  (binding [*print-length* nil]
    (let [updated-dependents (map normalize-to-unix-path
                                  (distinct (if (fs/directory? old-path)
                                              (rename-dir old-path new-path)
                                              (if (file/clojure-file? (File. old-path))
                                                (rename-clj-file old-path new-path)
                                                (rename-file! old-path new-path)))))]
      (if (fs/directory? old-path)
        updated-dependents
        (conj updated-dependents new-path)))))
