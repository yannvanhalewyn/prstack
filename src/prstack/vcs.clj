(ns prstack.vcs
  "Top-level VCS namespace that dispatches to specific VCS implementations.
  
  This namespace defines the VCS protocol that all version control backends
  must implement, and provides a unified interface for working with different
  VCS systems (Git, Jujutsu, etc.)."
  (:require
    [prstack.vcs.jujutsu :as jj]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Protocol Definition

(defprotocol VCS
  "Protocol defining version control system operations.
  
  This protocol abstracts over different VCS implementations (Git, Jujutsu, etc.)
  to provide a consistent interface for managing PR stacks.
  
  All methods refer to Malli schemas defined in prstack.vcs.graph for data
  structures like Graph, Node, and the legacy Change format."
  
  (vcs-config [this]
    "Returns the VCS configuration map.
    
    Returns:
      VCSConfig - A map containing:
        :vcs-config/trunk-branch - String, name of trunk branch (e.g., 'main' or 'master')
        
    Schema:
      [:map
       [:vcs-config/trunk-branch :string]]")
  
  (vcs-push-branch [this branch-name]
    "Pushes a local branch to the remote repository.
    
    Args:
      branch-name - String, the name of the branch to push
      
    Returns:
      String, the output from the push command
      
    Throws:
      ExceptionInfo if push fails")
  
  (vcs-trunk-moved? [this vcs-config]
    "Checks if the trunk branch has moved on the remote.
    
    Compares the local fork point with the remote trunk to determine if
    a rebase is needed.
    
    Args:
      vcs-config - VCSConfig map (from vcs-config method)
      
    Returns:
      Boolean, true if remote trunk has moved ahead of local fork point
      
    Schema:
      :boolean")
  
  (vcs-local-branchname [this change]
    "Extracts the local branch name from a change.
    
    Args:
      change - Change map (see prstack.vcs.graph/node->change)
      
    Returns:
      String or nil, the first local branch name without markers
      
    Schema:
      [:maybe :string]")
  
  (vcs-remote-branchname [this change]
    "Extracts the remote branch name from a change.
    
    Args:
      change - Change map (see prstack.vcs.graph/node->change)
      
    Returns:
      String or nil, the remote branch name without markers
      
    Schema:
      [:maybe :string]")
  
  (vcs-read-graph [this vcs-config]
    "Reads the full VCS graph from trunk to all bookmark heads.
    
    Builds a complete graph representation with parent/child relationships
    for all commits between trunk and bookmarked branches.
    
    Args:
      vcs-config - VCSConfig map (from vcs-config method)
      
    Returns:
      Graph - See prstack.vcs.graph/Graph
      
    Schema:
      [:map
       [:graph/nodes [:map-of :string Node]]
       [:graph/trunk-id :string]]
       
    Where Node is:
      [:map
       [:node/change-id :string]
       [:node/commit-sha {:optional true} :string]
       [:node/parents [:sequential :string]]
       [:node/children [:sequential :string]]
       [:node/local-branches [:sequential :string]]
       [:node/remote-branches [:sequential :string]]
       [:node/is-trunk? :boolean]
       [:node/is-merge? :boolean]]")
  
  (vcs-read-current-stack-graph [this vcs-config]
    "Reads a graph for the current working copy stack.
    
    Includes all changes from trunk to the current working copy, even if
    the current change is not bookmarked.
    
    Args:
      vcs-config - VCSConfig map (from vcs-config method)
      
    Returns:
      Graph - See prstack.vcs.graph/Graph (same schema as vcs-read-graph)")
  
  (vcs-current-change-id [this]
    "Returns the change-id of the current working copy.
    
    For Git, this would be the current commit SHA or HEAD.
    For Jujutsu, this is the change-id of @.
    
    Returns:
      String, identifier for current working copy
      
    Schema:
      :string"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VCS Instance Management

(def ^:dynamic *vcs-instance*
  "Dynamic var holding the current VCS implementation instance.
  
  Defaults to Jujutsu. Can be rebound to use Git or other implementations."
  (delay (jj/make-jujutsu-vcs)))

(defn set-vcs-instance!
  "Sets the global VCS instance.
  
  Args:
    vcs - A VCS protocol implementation instance"
  [vcs]
  (alter-var-root #'*vcs-instance* (constantly vcs)))

(defn get-vcs-instance
  "Returns the current VCS instance."
  []
  (if (delay? *vcs-instance*)
    @*vcs-instance*
    *vcs-instance*))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Convenience Functions (delegate to current VCS instance)

(defn config
  "Reads the VCS configuration from the current VCS instance."
  []
  (vcs-config (get-vcs-instance)))

(defn push-branch
  "Pushes a branch using the current VCS instance."
  [branch-name]
  (vcs-push-branch (get-vcs-instance) branch-name))

(defn trunk-moved?
  "Checks if trunk moved using the current VCS instance."
  [vcs-config]
  (vcs-trunk-moved? (get-vcs-instance) vcs-config))

(defn local-branchname
  "Gets local branch name using the current VCS instance."
  [change]
  (vcs-local-branchname (get-vcs-instance) change))

(defn remote-branchname
  "Gets remote branch name using the current VCS instance."
  [change]
  (vcs-remote-branchname (get-vcs-instance) change))

(defn read-graph
  "Reads the VCS graph using the current VCS instance.
  Returns a Graph (see prstack.vcs.graph/Graph)."
  [vcs-config]
  (vcs-read-graph (get-vcs-instance) vcs-config))

(defn read-current-stack-graph
  "Reads a graph for the current working copy stack using the current VCS instance.
  Includes all changes from trunk to @, even if @ is not bookmarked."
  [vcs-config]
  (vcs-read-current-stack-graph (get-vcs-instance) vcs-config))

(defn current-change-id
  "Returns the change-id of the current working copy using the current VCS instance."
  []
  (vcs-current-change-id (get-vcs-instance)))


