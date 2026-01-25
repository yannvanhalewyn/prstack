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

(defn fetch! []
  (u/run-cmd ["git" "fetch" "origin"]
    {:echo? true}))

(defn set-bookmark-to-remote! [branch-name]
  (u/run-cmd ["git" "branch" "-f" branch-name (str "origin/" branch-name)]
    {:echo? true}))

(defn rebase-on-trunk! [trunk-branch]
  (u/run-cmd ["git" "rebase" trunk-branch]
    {:echo? true}))

(defn push-tracked! []
  ;; Git doesn't have a built-in "push all tracked branches" command
  ;; We'll push the current branch with --force-with-lease
  ;; For a more complete implementation, we could iterate over all branches
  ;; that have upstream tracking configured
  (let [current-branch (str/trim (u/run-cmd ["git" "branch" "--show-current"]))]
    (when-not (str/blank? current-branch)
      (u/run-cmd ["git" "push" "origin" current-branch "--force-with-lease"]
        {:echo? true}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration

(defn- detect-trunk-branch
  "Detects whether the trunk branch is named 'master' or 'main'.

  Looks at local branches to determine which is the trunk."
  []
  (let [branches (str/split-lines
                   (u/run-cmd ["git" "branch" "--list" "master" "main"]))]
    (first
      (into []
        (comp
          (map str/trim)
          (map #(str/replace % #"^\*\s+" ""))
          (filter #{"master" "main"}))
        branches))))

(defn detect-trunk-branch! []
  (or (detect-trunk-branch)
      (throw (ex-info "Could not detect trunk branch" {}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Querying commit log

(defn commit-sha
  "Returns the commit SHA for a given ref."
  [ref]
  (str/trim (u/run-cmd ["git" "rev-parse" ref])))

(defn merge-base
  "Finds the merge base (common ancestor) between two refs."
  [ref1 ref2]
  (str/trim (u/run-cmd ["git" "merge-base" ref1 ref2])))

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
  [commit-sha]
  (let [output (u/run-cmd ["git" "rev-list" "--parents" "-n" "1" commit-sha])
        parts (str/split (str/trim output) #"\s+")]
    ;; First part is the commit itself, rest are parents
    (into [] (rest parts))))

(defn- get-branches []
  (->>
    (u/run-cmd ["git" "branch" "-v" "--all"
                "--format" "%(refname:lstrip=1)+++%(objectname)+++%(subject)"])
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
  (map :branch/name (get-branches)))

(defn get-commits-between
  "Gets all commit SHAs between trunk and the given ref (inclusive).

  Returns commits in topological order (oldest to newest)."
  [trunk-branch ref]
  (let [output (u/run-cmd ["git" "rev-list" "--topo-order" "--reverse"
                           (str trunk-branch ".." ref)])]
    (into []
      (comp
        (map str/trim)
        (remove empty?))
      (str/split-lines output))))

(defn get-all-commits-in-range
  "Gets all commits from trunk to all branch heads.

  Returns a set of unique commit SHAs."
  [trunk-branch]
  (let [;; Get all local branches except trunk
        branches (into []
                   (comp
                     (map str/trim)
                     (map #(str/replace % #"^\*\s+" ""))
                     (remove #{trunk-branch})
                     (remove #(str/starts-with? % "("))
                     (remove empty?))
                   (str/split-lines
                     (u/run-cmd ["git" "branch" "--list"])))
        ;; Get commits from trunk to each branch
        all-commits (mapcat #(get-commits-between trunk-branch %) branches)]
    (into #{} all-commits)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph operations

(defn- parse-change
  "Builds a node map from a commit SHA."
  [commit-sha trunk-sha branch-idx]
  ;; This is the reason git implementation is slow
  (let [parents (get-parent-shas commit-sha)]
    {:change/change-id commit-sha
     :change/commit-sha commit-sha
     :change/parent-ids parents
     :change/local-branchnames (->> (get branch-idx commit-sha)
                                 (remove remote-branch?)
                                 (map :branch/name))
     :change/remote-branchnames (->> (get branch-idx commit-sha)
                                  (filter remote-branch?)
                                  (map :branch/name))
     :change/trunk-node? (= commit-sha trunk-sha)}))

(defn parse-graph-commits
  "Parses a collection of commit SHAs into Change maps."
  [commit-shas trunk-sha]
  (let [branch-idx (group-by :branch/commit-sha (get-branches))]
    (into []
      (map #(parse-change % trunk-sha branch-idx))
      commit-shas)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VCS Implementation

(defn- find-fork-point [vcs ref]
  (merge-base ref (vcs/trunk-branch vcs)))

(defn- fork-info [vcs]
  (let [trunk-branch (vcs/trunk-branch vcs)]
    {:fork-info/fork-point-change-id (find-fork-point vcs "HEAD")
     :fork-info/local-trunk-change-id (commit-sha trunk-branch)
     :fork-info/remote-trunk-change-id (commit-sha (str "origin/" trunk-branch))}))

(defrecord GitVCS []
  vcs/VCS
  (read-vcs-config [_this]
    {:vcs-config/trunk-branch (detect-trunk-branch!)})

  (push-branch [_this branch-name]
    (u/run-cmd ["git" "push" "-u" "origin" branch-name "--force-with-lease"]
      {:echo? true}))

  (remote-branchname [_this change]
    (u/find-first
      #(str/starts-with? % "origin/")
      (:change/remote-branchnames change)))

  (read-all-nodes [this]
    (let [trunk-branch* (vcs/trunk-branch this)
          trunk-sha (commit-sha trunk-branch*)
          commit-shas (get-all-commits-in-range trunk-branch*)
          all-commits (conj commit-shas trunk-sha)
          nodes (parse-graph-commits all-commits trunk-sha)]
      {:nodes nodes
       :trunk-change-id trunk-sha}))

  (read-current-stack-nodes [this]
    (let [trunk-branch (:vcs-config/trunk-branch (vcs/vcs-config this))
          trunk-sha (commit-sha trunk-branch)
          head-sha (commit-sha "HEAD")
          commit-shas (get-commits-between trunk-branch "HEAD")
          all-commits (-> commit-shas
                        (conj trunk-sha)
                        (conj head-sha)
                        distinct
                        vec)]
      {:nodes (parse-graph-commits all-commits trunk-sha)
       :trunk-change-id trunk-sha}))

  (current-change-id [_this]
    (commit-sha "HEAD"))

  (find-fork-point [this ref]
    (find-fork-point this ref))

  (fork-info [this]
    (fork-info this))

  (fetch! [_this]
    (fetch!))

  (set-bookmark-to-remote! [_this branch-name]
    (set-bookmark-to-remote! branch-name))

  (rebase-on-trunk! [this]
    (rebase-on-trunk! (vcs/trunk-branch this)))

  (push-tracked! [_this]
    (push-tracked!)))
