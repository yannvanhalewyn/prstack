(ns prstack.vcs
  "Top-level VCS namespace that dispatches to specific VCS implementations.

  This namespace defines the VCS protocol that all version control backends
  must implement, and provides a unified interface for working with different
  VCS systems (Git, Jujutsu, etc.)."
  (:require
    [prstack.vcs.graph :as graph]))

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
      :string")

  (find-fork-point [this ref]
    "Finds the fork point (merge base) between a ref and trunk.

    This is the common ancestor where the ref diverged from the trunk line.
    Used to find the correct trunk anchor for stacks when trunk has advanced.

    Args:
      ref - String, the ref to find the fork point for (branch name, change-id, etc.)

    Returns:
      String, the change-id/commit-sha of the fork point

    Schema:
      :string")

  (fork-info [this]
    "{:fork-info/fork-point-id \"fork-point-change-id\"
      :fork-info/local-trunk-id \"local-trunk-change-id\"
      :fork-info/remote-trunk-id \"remote-trunk-change-id\"}"))

(defn vcs-config [vcs]
  (:vcs/config vcs))

(defn trunk-branch [vcs]
  (:vcs-config/trunk-branch (vcs-config vcs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reading Graph

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

(defn read-current-stack-graph [{:system/keys [user-config vcs]}]
  (let [{:keys [nodes trunk-change-id]} (read-current-stack-nodes vcs)
        nodes (map #(parse-change % user-config) nodes)]
    (graph/build-graph nodes trunk-change-id)))
