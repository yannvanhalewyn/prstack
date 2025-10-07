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
;; Stack operations

(defn get-leaves [vcs-config]
  (jj/get-leaves vcs-config))

(defn get-stack
  ([vcs-config]
   (jj/get-stack vcs-config))
  ([ref vcs-config]
   (jj/get-stack ref vcs-config)))

(def find-megamerge #'jj/find-megamerge)
(def parents #'jj/parents)
