(ns prstack.vcs
  "Top-level VCS namespace that dispatches to specific VCS implementations.

  This namespace defines the VCS protocol that all version control backends
  must implement, and provides a unified interface for working with different
  VCS systems (Git, Jujutsu, etc.)."
  (:require
    [clojure.string :as str]
    [prstack.vcs.graph :as graph]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Protocol Definition

(def ^:lsp/allow-unused VCSConfig
  [:map
   [:vcs-config/trunk-branch :string]])

(defprotocol VCS
  "Protocol defining version control system operations.

  This protocol abstracts over different VCS implementations (Git, Jujutsu, etc.)
  to provide a consistent interface for managing PR stacks.

  All methods refer to Malli schemas defined in prstack.vcs.graph for data
  structures like Graph, Node, and the legacy Change format."

  (read-vcs-config [this]
    "Returns the VCS configuration by consultiung the VCS

    Returns:
      VCSConfig - A map according to `VCSConfig` schema")

  (fetch! [this]
    "Fetches all branches from the remote repository.

     Side effects:
       Updates local tracking information from remote

     Returns:
       String, output from the fetch command")

  (push-branch! [this branch-name]
    "Pushes a local branch to the remote repository.

    Args:
      branch-name - String, the name of the branch to push

    Returns:
      String, the output from the push command

    Throws:
      ExceptionInfo if push fails")

  (read-relevant-changes [this]
    "Reads all relevant changes from the VCS graph. The relevant changes are
    the ones that have not been merged to the trunk yet. It should return a
    list of Changes with their parent IDs and leaf branchnames. The returned
    list of changes with parent-ids and branch-names will later be turned into
    a navigable graph (a Directed Acyclic Graph with bi-directional pointers).

     Returns:
       Map containing:
         :nodes - Vector of Change maps (see prstack.vcs.graph for schema)
         :trunk-change-id - String, the change-id/commit-sha of the trunk branch

     Schema:
     ```clojure
     [:map
      [:nodes [:sequential prstack.change/Change]]
      [:trunk-change-id :string]]
     ```")

  (current-change-id [this]
    "Returns the change-id of the current working copy.

    For Git, this would be the current commit SHA of HEAD.
    For Jujutsu, this is the change-id of @.

    Returns:
      String, identifier for current working copy")

  (find-fork-point [this ref]
    "Finds the fork point (merge base) between a ref and trunk.

    This is the common ancestor where the ref diverged from the trunk line.
    Used to find the correct trunk anchor for stacks when trunk has advanced.

    Args:
      ref - String, the ref to find the fork point for (branch name, change-id, etc.)

    Returns:
      String, the change-id/commit-sha of the fork point")

  (set-bookmark-to-remote! [this branch-name]
    "Sets a local branch/bookmark to match its remote counterpart.

     Args:
       branch-name - String, the name of the branch/bookmark to update

     Side effects:
       Updates local branch/bookmark to point to remote version

     Returns:
       String, output from the command")

  (delete-bookmark! [this bookmark-name]
    "Deletes a local bookmark/branch.

     Args:
       bookmark-name - String, the name of the bookmark/branch to delete

     Side effects:
       Removes the local bookmark/branch

     Returns:
       String, output from the command")

  (rebase-on! [this target-ref]
    "Rebases the current change/commit onto the given target ref.

     Args:
       target-ref - String, the ref to rebase onto (branch name, commit, etc.)

     Side effects:
       Rebases current working copy onto target

     Returns:
       String, output from the rebase command

     Throws:
       ExceptionInfo if rebase fails"))

(defn vcs-config [vcs]
  (:vcs/config vcs))

(defn trunk-branch [vcs]
  (:vcs-config/trunk-branch (vcs-config vcs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reading Graph

(defn- parse-change
  [change {:keys [ignored-branches]}]
  (let [;; Filter out ignored branches and remote refs (containing @)
        ;; Remote refs like "branch@origin" should not be selected as local branches
        local-only-branches (remove #(or (ignored-branches %)
                                         (str/includes? % "@"))
                              (:change/local-branchnames change))
        ;; Also filter remote branches by ignored-branches
        remote-branches (remove ignored-branches
                          (:change/remote-branchnames change))
        ;; Prefer local branch, fall back to remote branch
        selected-branch (or (first local-only-branches)
                            (first remote-branches))]
    (cond-> change
      selected-branch (assoc :change/selected-branchname selected-branch))))

(defn read-graph [vcs config]
  (let [{:keys [nodes trunk-change-id]} (read-relevant-changes vcs)
        nodes (map #(parse-change % config) nodes)]
    (graph/build-graph nodes trunk-change-id config)))
