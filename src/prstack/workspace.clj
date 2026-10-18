(ns prstack.workspace
  "A workspace is a directory that is not a repository itself, but contains
  multiple repositories. For example `~/work/client/` containing `api/`, `web/`
  and `infra/`. Running prstack from a workspace operates on all repositories
  found in it."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [prstack.repo :as repo]
    [prstack.utils :as u])
  (:import
    (java.io File)))

(def ^:private default-max-depth 3)

(def ^:private ignored-dirnames
  #{"node_modules" "vendor" "target" "dist" "build"})

(defn- repo-dir? [^File dir]
  (or (.exists (io/file dir ".git"))
      (.exists (io/file dir ".jj"))))

(defn repo-root
  "Returns the canonical path of the repository containing `dir`, which is
  `dir` itself or one of its parents, or nil when `dir` is not inside a
  repository. A nil `dir` means the current directory."
  [dir]
  (loop [^File f (.getCanonicalFile (io/file (or dir ".")))]
    (cond
      (nil? f) nil
      (repo-dir? f) (.getPath f)
      :else (recur (.getParentFile f)))))

(defn- scannable-dir? [^File f]
  (and (.isDirectory f)
       (not (str/starts-with? (.getName f) "."))
       (not (contains? ignored-dirnames (.getName f)))))

(defn find-repos
  "Returns the canonical paths of all repositories nested in `root`, sorted.
  Does not descend into repositories, so submodules and vendored repos are not
  included."
  ([root]
   (find-repos root {}))
  ([root {:keys [max-depth] :or {max-depth default-max-depth}}]
   (letfn [(scan [^File dir depth]
             (cond
               (repo-dir? dir) [(.getPath dir)]
               (< depth max-depth)
               (mapcat #(scan % (inc depth))
                 (filter scannable-dir? (.listFiles dir)))))]
     (->> (.listFiles (.getCanonicalFile (io/file root)))
       (filter scannable-dir?)
       (mapcat #(scan % 1))
       sort
       vec))))

(defn- relative-path [root path]
  (str (.relativize (.toPath (.getCanonicalFile (io/file root)))
         (.toPath (io/file path)))))

(defn repo-info
  "Returns the id and name of the repository at `repo-dir`, named after its path
  relative to the workspace."
  [workspace-dir repo-dir]
  {:repo/id (repo/id repo-dir)
   :repo/name (relative-path workspace-dir repo-dir)})

(defn repo-infos
  "Returns the id and name of every repository prstack operates on: all
  repositories in `:workspace-dir`, or else the one at `:project-dir`."
  [{:keys [workspace-dir project-dir]}]
  (if workspace-dir
    (mapv #(repo-info workspace-dir %) (find-repos workspace-dir))
    (let [repo-id (repo/id project-dir)]
      [{:repo/id repo-id
        :repo/name (repo/default-name repo-id)}])))

(defn open-repo
  "Opens the repository at `repo-dir` as a repo, see `prstack.repo/open`, named
  after its path relative to the workspace. When the repository could not be
  read, for example when it has no remote, the repo only has a `:repo/error`
  besides its id and name."
  [workspace-dir repo-dir]
  (let [info (repo-info workspace-dir repo-dir)]
    (try
      (merge (repo/open repo-dir) info)
      (catch Exception e
        (assoc info :repo/error (u/exception-message e))))))

(comment
  (repo-root nil)
  (repo-root "/tmp")
  (find-repos (str (System/getProperty "user.home") "/code"))
  (open-repo ".." "."))
