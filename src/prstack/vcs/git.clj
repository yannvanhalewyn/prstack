(ns prstack.vcs.git
  "Git implementation of the VCS protocol.

  This implementation provides Git-based version control operations that are
  designed to be a drop-in replacement for the Jujutsu implementation. It uses
  standard git commands to manage PR stacks."
  (:refer-clojure :exclude [parents])
  (:require
    [clojure.string :as str]
    [prstack.utils :as u]))

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
;; Basic operations

(defn commit-sha
  "Returns the commit SHA for a given ref."
  [ref]
  (str/trim (u/run-cmd ["git" "rev-parse" ref])))

(defn merge-base
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
        (remove #(str/starts-with? % "("))
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

(defn- build-node
  "Builds a node map from a commit SHA."
  [commit-sha trunk-sha]
  (let [parents (get-parent-shas commit-sha)
        local-branches (get-branches-at-commit commit-sha)
        remote-branches (get-remote-branches-at-commit commit-sha)]
    {:change/change-id commit-sha
     :change/commit-sha commit-sha
     :change/parent-ids parents
     :change/local-branchnames local-branches
     :change/remote-branchnames remote-branches
     :change/trunk-node? (= commit-sha trunk-sha)
     :change/merge-node? (> (count parents) 1)}))

(defn parse-graph-commits
  "Parses a collection of commit SHAs into node maps."
  [commit-shas trunk-sha]
  (into []
    (map #(build-node % trunk-sha))
    commit-shas))

(defn remove-asterisk-from-branch-name [branch-name]
  (when branch-name
    (str/replace branch-name #"^\*\s+" "")))
