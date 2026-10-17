(ns prstack.workspace
  "A workspace is a directory that is not a repository itself, but contains
  multiple repositories. For example `~/work/client/` containing `api/`, `web/`
  and `infra/`. Running prstack from a workspace operates on all repositories
  found in it."
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [prstack.config :as config]
    [prstack.repo :as repo]
    [prstack.vcs.jujutsu :as vcs.jj])
  (:import
    (java.io File)))

(def ^:private default-max-depth 3)

(def ^:private ignored-dirnames
  #{"node_modules" "vendor" "target" "dist" "build"})

(defn- repo-dir? [^File dir]
  (or (.exists (io/file dir ".git"))
      (.exists (io/file dir ".jj"))))

(defn inside-repo?
  "Returns true when `dir`, or any of its parents, is a repository. A nil `dir`
  means the current directory."
  [dir]
  (loop [^File f (.getCanonicalFile (io/file (or dir ".")))]
    (cond
      (nil? f) false
      (repo-dir? f) true
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

(defn- default-local-config
  "Config used for repositories that were never initialized with prstack. We
  don't want to prompt for setup in every nested repository, so infer the VCS
  instead."
  [repo-dir]
  {:vcs (if (and (.exists (io/file repo-dir ".jj")) (vcs.jj/installed?))
          :jujutsu
          :git)
   :ignored-branches #{}
   :feature-base-branches #{}})

(defn- relative-path [root path]
  (str (.relativize (.toPath (.getCanonicalFile (io/file root)))
         (.toPath (io/file path)))))

(defn open-repo
  "Opens the repository at `repo-dir` as a repo, see `prstack.repo/open`, named
  after its path relative to the workspace. When the repository could not be
  read, for example when it has no remote, the repo only has a `:repo/error`
  besides its id and name."
  [workspace-dir repo-dir]
  (let [repo-info {:repo/id (repo/id repo-dir)
                   :repo/name (relative-path workspace-dir repo-dir)}]
    (try
      (merge
        (repo/open repo-dir
          (or (config/read-local repo-dir)
              (default-local-config repo-dir)))
        repo-info)
      (catch Exception e
        (assoc repo-info :repo/error (or (some-> (ex-message e) str/trim)
                                         (str (class e))))))))

(comment
  (inside-repo? nil)
  (inside-repo? "/tmp")
  (find-repos (str (System/getProperty "user.home") "/code"))
  (open-repo ".." "."))
