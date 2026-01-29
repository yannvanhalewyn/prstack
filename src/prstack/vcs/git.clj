(ns prstack.vcs.git
  "Git implementation of the VCS protocol.

  This implementation provides Git-based version control operations that are
  designed to be a drop-in replacement for the Jujutsu implementation. It uses
  standard git commands to manage PR stacks."
  (:refer-clojure :exclude [parents])
  (:require
    [clojure.string :as str]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Branch Operations

(defn fetch! [opts]
  (u/run-cmd ["git" "fetch" "origin"]
    (merge {:echo? true} opts)))

(defn set-bookmark-to-remote! [branch-name opts]
  (u/run-cmd ["git" "branch" "-f" branch-name (str "origin/" branch-name)]
    (merge {:echo? true} opts)))

;; TODO don't think this accurately rebases all PRs in the stack
;; Likely I'll need to support a few rebasing strategies, like using machete,
;; graphite, or a custom implementation
(defn rebase-on-trunk! [trunk-branch opts]
  (u/run-cmd ["git" "rebase" trunk-branch]
    (merge {:echo? true} opts)))

(defn push-tracked! [opts]
  ;; Git doesn't have a built-in "push all tracked branches" command
  ;; We'll push the current branch with --force-with-lease
  ;; For a more complete implementation, we could iterate over all branches
  ;; that have upstream tracking configured
  (let [current-branch (str/trim (u/run-cmd ["git" "branch" "--show-current"] opts))]
    (when-not (str/blank? current-branch)
      (u/run-cmd ["git" "push" "origin" current-branch "--force-with-lease"]
        (merge {:echo? true} opts)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration

(defn- detect-trunk-branch
  "Detects whether the trunk branch is named 'master' or 'main'.

  Looks at local branches to determine which is the trunk."
  [opts]
  (let [branches (str/split-lines
                   (u/run-cmd ["git" "branch" "--list" "master" "main"] opts))]
    (first
      (into []
        (comp
          (map str/trim)
          (map #(str/replace % #"^\*\s+" ""))
          (filter #{"master" "main"}))
        branches))))

(defn detect-trunk-branch! [opts]
  (or (detect-trunk-branch opts)
      (throw (ex-info "Could not detect trunk branch" {}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Querying commit log

(defn commit-sha
  "Returns the commit SHA for a given ref."
  [ref opts]
  (str/trim (u/run-cmd ["git" "rev-parse" ref] opts)))

(defn merge-base
  "Finds the merge base (common ancestor) between two refs."
  [ref1 ref2 opts]
  (str/trim (u/run-cmd ["git" "merge-base" ref1 ref2] opts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parsing Branche Info

;; Example line:
;; docs-prod+++5c090cb3+++(refactor): remove `:node/` namespace in favor of `:change/`
(defn- parse-branch-info [branch-line]
  (when-let [[branchname sha subject] (str/split branch-line #"\+\+\+" 3)]
    (let [[_ local-branchname]
          (re-find #"heads/(.*)" branchname)
          [_ remote-name remote-branchname]
          (re-find #"remotes/(.*)/(.*)" branchname)]
      (cond->
        {:branch/name (or local-branchname remote-branchname)
         :branch/commit-sha sha
         :branch/title subject}
        remote-name (assoc :branch/remote remote-name)))))

(defn- remote-branch? [branch]
  (contains? branch :branch/remote))

(defn- get-parent-shas
  "Returns the parent commit SHAs for a given commit."
  [sha opts]
  (let [output (u/run-cmd ["git" "rev-list" "--parents" "-n" "1" sha] opts)
        parts (str/split (str/trim output) #"\s+")]
    ;; First part is the commit itself, rest are parents
    (into [] (rest parts))))

(defn- get-branches [opts]
  (->>
    (u/run-cmd ["git" "branch" "-v" "--all"
                "--format" "%(refname:lstrip=1)+++%(objectname)+++%(subject)"]
      opts)
    (str/split-lines)
    (into []
      (comp
        (map str/trim)
        ;; (HEAD detached at ...)
        (remove #(str/starts-with? % "("))
        (keep parse-branch-info)))))

(comment
  (parse-branch-info
    "heads/docs/add-workflows-and-diagrams+++7534a572+++(feat): add code example component")
  (map :branch/name (get-branches {:dir "tmp/parallel-branches"})))

(defn get-commits-between
  "Gets all commit SHAs between trunk and the given ref (inclusive).

  Returns commits in topological order (oldest to newest)."
  [trunk-branch ref opts]
  (let [output (u/run-cmd ["git" "rev-list" "--topo-order" "--reverse"
                           (str trunk-branch ".." ref)]
                 opts)]
    (into []
      (comp
        (map str/trim)
        (remove empty?))
      (str/split-lines output))))

(defn get-all-commits-in-range
  "Gets all commits from trunk to all branch heads.

  Returns a set of unique commit SHAs."
  [trunk-branch opts]
  (let [;; Get all local branches except trunk
        branches (into []
                   (comp
                     (map str/trim)
                     (map #(str/replace % #"^\*\s+" ""))
                     (remove #{trunk-branch})
                     (remove #(str/starts-with? % "("))
                     (remove empty?))
                   (str/split-lines
                     (u/run-cmd ["git" "branch" "--list"] opts)))
        ;; Get commits from trunk to each branch
        all-commits (mapcat #(get-commits-between trunk-branch % opts) branches)]
    (into #{} all-commits)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph operations

(defn- parse-change
  "Builds a node map from a commit SHA."
  [sha trunk-sha branch-idx opts]
  ;; This is the reason git implementation is slow
  (let [parents (get-parent-shas sha opts)]
    {:change/change-id sha
     :change/commit-sha sha
     :change/parent-ids parents
     :change/local-branchnames (->> (get branch-idx sha)
                                 (remove remote-branch?)
                                 (map :branch/name))
     :change/remote-branchnames (->> (get branch-idx sha)
                                  (filter remote-branch?)
                                  (map :branch/name))
     :change/trunk-node? (= sha trunk-sha)}))

(defn parse-graph-commits
  "Parses a collection of commit SHAs into Change maps."
  [commit-shas trunk-sha opts]
  (let [branch-idx (group-by :branch/commit-sha (get-branches opts))]
    (into []
      (map #(parse-change % trunk-sha branch-idx opts))
      commit-shas)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VCS Implementation

(defn- find-fork-point* [vcs ref]
  (let [opts {:dir (:project-dir vcs)}]
    (merge-base ref (vcs/trunk-branch vcs) opts)))

(defn- fork-info [vcs]
  (let [trunk-branch (vcs/trunk-branch vcs)
        opts {:dir (:project-dir vcs)}]
    {:forkpoint-info/fork-point-change-id (find-fork-point* vcs "HEAD")
     :forkpoint-info/local-trunk-commit-sha (commit-sha trunk-branch opts)
     :forkpoint-info/remote-trunk-commit-sha (commit-sha (str "origin/" trunk-branch) opts)}))

(defrecord GitVCS []
  vcs/VCS
  (read-vcs-config [this]
    {:vcs-config/trunk-branch (detect-trunk-branch! {:dir (:project-dir this)})})

  (push-branch [this branch-name]
    (u/run-cmd ["git" "push" "-u" "origin" branch-name "--force-with-lease"]
      {:echo? true :dir (:project-dir this)}))

  (remote-branchname [_this change]
    (u/find-first
      #(str/starts-with? % "origin/")
      (:change/remote-branchnames change)))

  (read-all-nodes [this]
    (let [opts {:dir (:project-dir this)}
          trunk-branch* (vcs/trunk-branch this)
          trunk-sha (commit-sha trunk-branch* opts)
          commit-shas (get-all-commits-in-range trunk-branch* opts)
          all-commits (conj commit-shas trunk-sha)
          nodes (parse-graph-commits all-commits trunk-sha opts)]
      {:nodes nodes
       :trunk-change-id trunk-sha}))

  (read-current-stack-nodes [this]
    (let [opts {:dir (:project-dir this)}
          trunk-branch (:vcs-config/trunk-branch (vcs/vcs-config this))
          trunk-sha (commit-sha trunk-branch opts)
          head-sha (commit-sha "HEAD" opts)
          commit-shas (get-commits-between trunk-branch "HEAD" opts)
          all-commits (-> commit-shas
                        (conj trunk-sha)
                        (conj head-sha)
                        distinct
                        vec)]
      {:nodes (parse-graph-commits all-commits trunk-sha opts)
       :trunk-change-id trunk-sha}))

  (current-change-id [this]
    (commit-sha "HEAD" {:dir (:project-dir this)}))

  (find-fork-point [this ref]
    (find-fork-point* this ref))

  (fork-info [this]
    (fork-info this))

  (fetch! [this]
    (fetch! {:dir (:project-dir this)}))

  (set-bookmark-to-remote! [this branch-name]
    (set-bookmark-to-remote! branch-name {:dir (:project-dir this)}))

  (rebase-on-trunk! [this]
    (rebase-on-trunk! (vcs/trunk-branch this) {:dir (:project-dir this)}))

  (push-tracked! [this]
    (push-tracked! {:dir (:project-dir this)})))
