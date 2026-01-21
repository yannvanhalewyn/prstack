(ns prstack.vcs.git
  "Git implementation of the VCS protocol.
  
  This implementation provides Git-based version control operations that are
  designed to be a drop-in replacement for the Jujutsu implementation. It uses
  standard git commands to manage PR stacks."
  (:refer-clojure :exclude [parents])
  (:require
    [bb-tty.ansi :as ansi]
    [clojure.string :as str]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]
    [prstack.vcs.graph :as graph]))

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

(defn- detect-trunk-branch! []
  (or (detect-trunk-branch)
      (throw (ex-info "Could not detect trunk branch" {}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Basic operations

(defn- ^:lsp/allow-unused current-branch
  "Returns the name of the current branch, or nil if in detached HEAD state."
  []
  (try
    (str/trim (u/run-cmd ["git" "symbolic-ref" "--short" "HEAD"]))
    (catch Exception _
      nil)))

(defn- commit-sha
  "Returns the commit SHA for a given ref."
  [ref]
  (str/trim (u/run-cmd ["git" "rev-parse" ref])))

(defn- ^:lsp/allow-unused branch-exists?
  "Checks if a branch exists locally."
  [branch-name]
  (try
    (u/run-cmd ["git" "rev-parse" "--verify" (str "refs/heads/" branch-name)])
    true
    (catch Exception _
      false)))

(defn- ^:lsp/allow-unused remote-branch-exists?
  "Checks if a branch exists on the remote."
  [branch-name]
  (try
    (u/run-cmd ["git" "rev-parse" "--verify" (str "origin/" branch-name)])
    true
    (catch Exception _
      false)))

(defn- merge-base
  "Finds the merge base (common ancestor) between two refs."
  [ref1 ref2]
  (str/trim (u/run-cmd ["git" "merge-base" ref1 ref2])))

(defn- get-branches-at-commit
  "Returns all local branches pointing to the given commit SHA."
  [commit-sha]
  (let [output (u/run-cmd ["git" "branch" "--points-at" commit-sha])]
    (into []
      (comp
        (map str/trim)
        (map #(str/replace % #"^\*\s+" ""))
        (remove empty?))
      (str/split-lines output))))

(defn- get-remote-branches-at-commit
  "Returns all remote branches pointing to the given commit SHA."
  [commit-sha]
  (let [output (u/run-cmd ["git" "branch" "-r" "--points-at" commit-sha])]
    (into []
      (comp
        (map str/trim)
        (remove #(str/includes? % "HEAD ->"))
        (remove empty?))
      (str/split-lines output))))

(defn- get-parent-shas
  "Returns the parent commit SHAs for a given commit."
  [commit-sha]
  (let [output (u/run-cmd ["git" "rev-list" "--parents" "-n" "1" commit-sha])
        parts (str/split (str/trim output) #"\s+")]
    ;; First part is the commit itself, rest are parents
    (into [] (rest parts))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph operations

(defn- get-commits-between
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

(defn- get-all-commits-in-range
  "Gets all commits from trunk to all branch heads.
  
  Returns a set of unique commit SHAs."
  [trunk-branch]
  (let [;; Get all local branches except trunk
        branches (into []
                   (comp
                     (map str/trim)
                     (map #(str/replace % #"^\*\s+" ""))
                     (remove #{trunk-branch})
                     (remove empty?))
                   (str/split-lines
                     (u/run-cmd ["git" "branch" "--list"])))
        ;; Get commits from trunk to each branch
        all-commits (mapcat #(get-commits-between trunk-branch %) branches)]
    (into #{} all-commits)))

(defn- build-node
  "Builds a node map from a commit SHA."
  [commit-sha trunk-sha]
  (let [parents (get-parent-shas commit-sha)
        local-branches (get-branches-at-commit commit-sha)
        remote-branches (get-remote-branches-at-commit commit-sha)]
    {:node/change-id commit-sha
     :node/commit-sha commit-sha
     :node/parents parents
     :node/local-branches local-branches
     :node/remote-branches remote-branches
     :node/is-trunk? (= commit-sha trunk-sha)
     :node/is-merge? (> (count parents) 1)}))

(defn- parse-graph-commits
  "Parses a collection of commit SHAs into node maps."
  [commit-shas trunk-sha]
  (into []
    (map #(build-node % trunk-sha))
    commit-shas))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Change data structure

(defn- remove-asterisk-from-branch-name [branch-name]
  (when branch-name
    (str/replace branch-name #"^\*\s+" "")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VCS Protocol Implementation

(deftype GitVCS []
  vcs/VCS
  
  (vcs-config [_this]
    {:vcs-config/trunk-branch (detect-trunk-branch!)})
  
  (vcs-push-branch [_this branch-name]
    (u/run-cmd ["git" "push" "-u" "origin" branch-name "--force-with-lease"]
      {:echo? true}))
  
  (vcs-trunk-moved? [_this {:vcs-config/keys [trunk-branch]}]
    (let [;; Get the merge base between current HEAD and trunk
          fork-point (merge-base "HEAD" trunk-branch)
          ;; Get the remote trunk commit
          remote-trunk (commit-sha (str "origin/" trunk-branch))]
      (println (ansi/colorize :yellow "\nChecking if trunk moved"))
      (println (ansi/colorize :cyan "Fork point") fork-point)
      (println (ansi/colorize :cyan (str "remote " trunk-branch)) remote-trunk)
      (not= fork-point remote-trunk)))
  
  (vcs-local-branchname [_this change]
    (remove-asterisk-from-branch-name
      (first (:change/local-branches change))))
  
  (vcs-remote-branchname [_this change]
    (remove-asterisk-from-branch-name
      (u/find-first
        #(str/starts-with? % "origin/")
        (:change/remote-branches change))))
  
  (vcs-read-graph [_this {:vcs-config/keys [trunk-branch]}]
    (let [;; Get trunk commit SHA
          trunk-sha (commit-sha trunk-branch)
          ;; Get all commits in the range from trunk to all branches
          commit-shas (get-all-commits-in-range trunk-branch)
          ;; Add trunk itself
          all-commits (conj commit-shas trunk-sha)
          ;; Build nodes
          nodes (parse-graph-commits all-commits trunk-sha)]
      (graph/build-graph nodes trunk-sha)))
  
  (vcs-read-current-stack-graph [_this {:vcs-config/keys [trunk-branch]}]
    (let [;; Get trunk commit SHA
          trunk-sha (commit-sha trunk-branch)
          ;; Get current HEAD
          head-sha (commit-sha "HEAD")
          ;; Get commits from trunk to HEAD
          commit-shas (get-commits-between trunk-branch "HEAD")
          ;; Add trunk and HEAD
          all-commits (-> commit-shas
                        (conj trunk-sha)
                        (conj head-sha)
                        distinct
                        vec)
          ;; Build nodes
          nodes (parse-graph-commits all-commits trunk-sha)]
      (graph/build-graph nodes trunk-sha)))
  
  (vcs-current-change-id [_this]
    (commit-sha "HEAD")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API

(defn make-git-vcs
  "Creates a new Git VCS implementation instance."
  []
  (->GitVCS))

(comment
  ;; Example usage
  (def git-vcs (make-git-vcs))
  (def config (vcs/config git-vcs))
  (def graph (vcs/read-graph git-vcs config))
  (vcs/current-change-id git-vcs))
