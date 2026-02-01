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

(defn fetch! [vcs]
  (u/run-cmd ["git" "fetch" "origin"]
    {:echo? true :dir (:vcs/project-dir vcs)}))

(defn set-bookmark-to-remote! [vcs branch-name]
  (u/run-cmd ["git" "branch" "-f" branch-name (str "origin/" branch-name)]
    {:echo? true :dir (:vcs/project-dir vcs)}))

;; TODO don't think this accurately rebases all PRs in the stack
;; Likely I'll need to support a few rebasing strategies, like using machete,
;; graphite, or a custom implementation
(defn rebase-on-trunk! [vcs]
  (u/run-cmd ["git" "rebase" (vcs/trunk-branch vcs)]
    {:echo? true :dir (:vcs/project-dir vcs)}))

(defn push-tracked! [vcs]
  ;; Git doesn't have a built-in "push all tracked branches" command
  ;; We'll push the current branch with --force-with-lease
  ;; For a more complete implementation, we could iterate over all branches
  ;; that have upstream tracking configured
  (let [current-branch (str/trim (u/run-cmd ["git" "branch" "--show-current"]
                                   {:dir (:vcs/project-dir vcs)}))]
    (when-not (str/blank? current-branch)
      (u/run-cmd ["git" "push" "origin" current-branch "--force-with-lease"]
        (merge {:echo? true} {:dir (:vcs/project-dir vcs)})))))

(defn delete-bookmark! [vcs branch-name]
  (u/run-cmd ["git" "branch" "-D" branch-name]
    {:dir (:vcs/project-dir vcs) :echo? true}))

(defn list-local-bookmarks [vcs]
  (let [output (u/run-cmd ["git" "branch" "--list" "--format" "%(refname:short)"]
                 {:dir (:vcs/project-dir vcs)})]
    (into []
      (comp
        (map str/trim)
        (remove empty?))
      (str/split-lines output))))

(defn get-change-id [vcs ref]
  (str/trim (u/run-cmd ["git" "rev-parse" ref]
              {:dir (:vcs/project-dir vcs)})))

(defn rebase-on! [vcs target-ref]
  (u/run-cmd ["git" "rebase" target-ref]
    {:dir (:vcs/project-dir vcs) :echo? true}))

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
  [vcs ref]
  (str/trim (u/run-cmd ["git" "rev-parse" ref]
              {:dir (:vcs/project-dir vcs)})))

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
  [vcs sha]
  (let [output (u/run-cmd ["git" "rev-list" "--parents" "-n" "1" sha]
                 {:dir (:vcs/project-dir vcs)})
        parts (str/split (str/trim output) #"\s+")]
    ;; First part is the commit itself, rest are parents
    (into [] (rest parts))))

(defn- get-branches [vcs]
  (->>
    (u/run-cmd ["git" "branch" "-v" "--all"
                "--format" "%(refname:lstrip=1)+++%(objectname)+++%(subject)"]
      {:dir (:vcs/project-dir vcs)})
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
  (map :branch/name (get-branches {:vcs/project-dir "tmp/parallel-branches"})))

(defn get-commits-between
  "Gets all commit SHAs between trunk and the given ref (inclusive).

  Returns commits in topological order (oldest to newest)."
  [vcs trunk-branch ref]
  (let [output (u/run-cmd ["git" "rev-list" "--topo-order" "--reverse"
                           (str trunk-branch ".." ref)]
                 {:dir (:vcs/project-dir vcs)})]
    (into []
      (comp
        (map str/trim)
        (remove empty?))
      (str/split-lines output))))

(defn get-all-commits-in-range
  "Gets all commits from trunk to all branch heads.

  Returns a set of unique commit SHAs."
  [vcs trunk-branch]
  (let [;; Get all local branches except trunk
        branches (into []
                   (comp
                     (map str/trim)
                     (map #(str/replace % #"^\*\s+" ""))
                     (remove #{trunk-branch})
                     (remove #(str/starts-with? % "("))
                     (remove empty?))
                   (str/split-lines
                     (u/run-cmd ["git" "branch" "--list"]
                       {:dir (:vcs/project-dir vcs)})))
        ;; Get commits from trunk to each branch
        all-commits (mapcat #(get-commits-between vcs trunk-branch %) branches)]
    (into #{} all-commits)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph operations

(defn- parse-change
  "Builds a node map from a commit SHA."
  [sha vcs trunk-sha branch-idx]
  ;; This is the reason git implementation is slow
  (let [parents (get-parent-shas vcs sha)]
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
  [vcs commit-shas trunk-sha]
  (let [branch-idx (group-by :branch/commit-sha (get-branches vcs))]
    (into []
      (map #(parse-change % vcs trunk-sha branch-idx))
      commit-shas)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VCS Implementation

(defn- find-fork-point* [vcs ref]
  (let [opts {:dir (:vcs/project-dir vcs)}]
    (merge-base ref (vcs/trunk-branch vcs) opts)))

(defn- fork-info [vcs]
  (let [trunk-branch (vcs/trunk-branch vcs)
        opts {:dir (:vcs/project-dir vcs)}]
    {:forkpoint-info/fork-point-change-id (find-fork-point* vcs "HEAD")
     :forkpoint-info/local-trunk-commit-sha (commit-sha trunk-branch opts)
     :forkpoint-info/remote-trunk-commit-sha (commit-sha (str "origin/" trunk-branch) opts)}))

(defrecord GitVCS []
  vcs/VCS
  (read-vcs-config [this]
    {:vcs-config/trunk-branch (detect-trunk-branch! {:dir (:vcs/project-dir this)})})

  (push-branch! [this branch-name]
    (u/run-cmd ["git" "push" "-u" "origin" branch-name "--force-with-lease"]
      {:echo? true :dir (:vcs/project-dir this)}))

  (remote-branchname [_this change]
    (u/find-first
      #(str/starts-with? % "origin/")
      (:change/remote-branchnames change)))

  (read-all-nodes [this]
    (let [trunk-branch* (vcs/trunk-branch this)
          trunk-sha (commit-sha this trunk-branch*)
          commit-shas (get-all-commits-in-range this trunk-branch*)
          all-commits (conj commit-shas trunk-sha)
          nodes (parse-graph-commits this all-commits trunk-sha)]
      {:nodes nodes
       :trunk-change-id trunk-sha}))

  (read-current-stack-nodes [this]
    (let [trunk-branch (:vcs-config/trunk-branch (vcs/vcs-config this))
          trunk-sha (commit-sha this trunk-branch)
          head-sha (commit-sha this "HEAD")
          commit-shas (get-commits-between this trunk-branch "HEAD")
          all-commits (-> commit-shas
                        (conj trunk-sha)
                        (conj head-sha)
                        distinct
                        vec)]
      {:nodes (parse-graph-commits this all-commits trunk-sha)
       :trunk-change-id trunk-sha}))

  (current-change-id [this]
    (commit-sha this "HEAD"))

  (find-fork-point [this ref]
    (find-fork-point* this ref))

  (fork-info [this]
    (fork-info this))

  (fetch! [this]
    (fetch! this))

  (set-bookmark-to-remote! [this branch-name]
    (set-bookmark-to-remote! this branch-name))

  (rebase-on-trunk! [this]
    (rebase-on-trunk! this))

  (push-tracked! [this]
    (push-tracked! this))

  (delete-bookmark! [this bookmark-name]
    (delete-bookmark! this bookmark-name))

  (list-local-bookmarks [this]
    (list-local-bookmarks this))

  (get-change-id [this ref]
    (get-change-id this ref))

  (rebase-on! [this target-ref]
    (rebase-on! this target-ref)))
