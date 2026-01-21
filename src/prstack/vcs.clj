(ns prstack.vcs
  "Top-level VCS namespace that dispatches to specific VCS implementations"
  (:require
    [prstack.vcs.jujutsu :as jj]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration

(defn config
  "Reads the VCS configuration"
  []
  (jj/config))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Branches

(defn push-branch [branch-name]
  (jj/push-branch branch-name))

(defn trunk-moved? [vcs-config]
  (jj/trunk-moved? vcs-config))

(defn local-branchname [change]
  (jj/local-branchname change))

(defn remote-branchname [change]
  (jj/remote-branchname change))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph operations

(defn read-graph
  "Reads the VCS graph. Returns a Graph (see prstack.vcs.graph/Graph)."
  [vcs-config]
  (jj/read-graph vcs-config))

(defn read-current-stack-graph
  "Reads a graph for the current working copy stack.
  Includes all changes from trunk to @, even if @ is not bookmarked."
  [vcs-config]
  (jj/read-current-stack-graph vcs-config))

(defn current-change-id
  "Returns the change-id of the current working copy."
  []
  (jj/current-change-id))


