(ns prstack.vcs
  "Top-level VCS namespace that dispatches to specific VCS implementations.

  This namespace defines the VCS protocol that all version control backends
  must implement, and provides a unified interface for working with different
  VCS systems (Git, Jujutsu, etc.)."
  (:require
    [bb-tty.ansi :as ansi]
    [clojure.string :as str]
    [prstack.utils :as u]
    [prstack.vcs.git :as git]
    [prstack.vcs.graph :as graph]
    [prstack.vcs.jujutsu :as jj]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Protocol Definition

(defprotocol VCS
  "Protocol defining version control system operations.

  This protocol abstracts over different VCS implementations (Git, Jujutsu, etc.)
  to provide a consistent interface for managing PR stacks.

  All methods refer to Malli schemas defined in prstack.vcs.graph for data
  structures like Graph, Node, and the legacy Change format."

  (read-vcs-config [this]
    "Returns the VCS configuration by consultiung the VCS

    Returns:
      VCSConfig - A map containing:
        :vcs-config/trunk-branch - String, name of trunk branch (e.g., 'main' or 'master')

    Schema:
      [:map
       [:vcs-config/trunk-branch :string]]")

  (push-branch [this branch-name]
    "Pushes a local branch to the remote repository.

    Args:
      branch-name - String, the name of the branch to push

    Returns:
      String, the output from the push command

    Throws:
      ExceptionInfo if push fails")

  (trunk-moved? [this]
    "Checks if the trunk branch has moved on the remote.

    Compares the local fork point with the remote trunk to determine if
    a rebase is needed.

    Returns:
      Boolean, true if remote trunk has moved ahead of local fork point

    Schema:
      :boolean")

  (remote-branchname [this change]
    "Extracts the remote branch name from a change.

    Args:
      change - Change map (see prstack.vcs.graph/node->change)

    Returns:
      String or nil, the remote branch name without markers

    Schema:
      [:maybe :string]")

  (read-all-nodes [this])

  (read-current-stack-nodes [this]
    "TODO fix Reads a graph for the current working copy stack.

    Includes all changes from trunk to the current working copy, even if
    the current change is not bookmarked.

    Returns:
      Graph - See prstack.vcs.graph/Graph (same schema as read-graph)")

  (current-change-id [this]
    "Returns the change-id of the current working copy.

    For Git, this would be the current commit SHA or HEAD.
    For Jujutsu, this is the change-id of @.

    Returns:
      String, identifier for current working copy

    Schema:
      :string"))

(defn vcs-config [vcs]
  (:vcs/config vcs))

(defn trunk-branch [vcs]
  (:vcs-config/trunk-branch (vcs-config vcs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VCS Protocol Implementation

(defrecord JujutsuVCS []
  VCS
  (read-vcs-config [_this]
    (jj/config))

  (push-branch [_this branch-name]
    (jj/push-branch branch-name))

  (trunk-moved? [this]
    (jj/trunk-moved? (vcs-config this)))

  (remote-branchname [_this change]
    (jj/remote-branchname change))

  (read-all-nodes [this]
    (jj/read-all-nodes (vcs-config this)))

  (read-current-stack-nodes [this]
    (jj/read-current-stack-nodes (vcs-config this)))

  (current-change-id [_this]
    (jj/current-change-id)))

(defn- parse-change
  [change {:keys [ignored-branches feature-base-branches]}]
  (let [selected-branch (first (remove ignored-branches (:change/local-branchnames change)))
        feature-base? (and selected-branch (contains? feature-base-branches selected-branch))
        type
        (cond
          (:change/trunk-node? change) :trunk
          feature-base? :feature-base
          :else :regular)]
    (cond-> (assoc change :change/type type)
      selected-branch (assoc :change/selected-branchname selected-branch))))

(defn read-graph [vcs config]
  (let [{:keys [nodes trunk-change-id]} (read-all-nodes vcs)
        nodes (map #(parse-change % config) nodes)]
    (graph/build-graph nodes trunk-change-id)))

(defn read-current-stack-graph [vcs config]
  (let [{:keys [nodes trunk-change-id]} (read-current-stack-nodes vcs)
        nodes (map #(parse-change % config) nodes)]
    (graph/build-graph nodes trunk-change-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Git Implementation

(defrecord GitVCS []
  VCS
  (read-vcs-config [_this]
    {:vcs-config/trunk-branch (git/detect-trunk-branch!)})

  (push-branch [_this branch-name]
    (u/run-cmd ["git" "push" "-u" "origin" branch-name "--force-with-lease"]
      {:echo? true}))

  (trunk-moved? [this]
    (let [trunk-branch (:vcs-config/trunk-branch (vcs-config this))
          fork-point (git/merge-base "HEAD" trunk-branch)
          remote-trunk (git/commit-sha (str "origin/" trunk-branch))]
      (println (ansi/colorize :yellow "\nChecking if trunk moved"))
      (println (ansi/colorize :cyan "Fork point") fork-point)
      (println (ansi/colorize :cyan (str "remote " trunk-branch)) remote-trunk)
      (not= fork-point remote-trunk)))

  (remote-branchname [_this change]
    (u/find-first
      #(str/starts-with? % "origin/")
      (:change/remote-branchnames change)))

  (read-all-nodes [this]
    (let [trunk-branch* (trunk-branch this)
          trunk-sha (git/commit-sha trunk-branch*)
          commit-shas (git/get-all-commits-in-range trunk-branch*)
          all-commits (conj commit-shas trunk-sha)
          nodes (git/parse-graph-commits all-commits trunk-sha)]
      {:nodes nodes
       :trunk-change-id trunk-sha}))

  (read-current-stack-nodes [this]
    (let [trunk-branch (:vcs-config/trunk-branch (vcs-config this))
          trunk-sha (git/commit-sha trunk-branch)
          head-sha (git/commit-sha "HEAD")
          commit-shas (git/get-commits-between trunk-branch "HEAD")
          all-commits (-> commit-shas
                        (conj trunk-sha)
                        (conj head-sha)
                        distinct
                        vec)]
      {:nodes (git/parse-graph-commits all-commits trunk-sha)
       :trunk-change-id trunk-sha}))

  (current-change-id [_this]
    (git/commit-sha "HEAD")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API

(defn make [config]
  (let [vcs (if (= (:vcs config) :git)
              (->GitVCS)
              (->JujutsuVCS))]
    (assoc vcs :vcs/config (read-vcs-config vcs))))
