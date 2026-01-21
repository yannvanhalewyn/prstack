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
  
  (config [this]
    "Returns the VCS configuration map.
    
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
  
  (trunk-moved? [this vcs-config]
    "Checks if the trunk branch has moved on the remote.
    
    Compares the local fork point with the remote trunk to determine if
    a rebase is needed.
    
    Args:
      vcs-config - VCSConfig map (from config method)
      
    Returns:
      Boolean, true if remote trunk has moved ahead of local fork point
      
    Schema:
      :boolean")
  
  (local-branchname [this change]
    "Extracts the local branch name from a change.
    
    Args:
      change - Change map (see prstack.vcs.graph/node->change)
      
    Returns:
      String or nil, the first local branch name without markers
      
    Schema:
      [:maybe :string]")
  
  (remote-branchname [this change]
    "Extracts the remote branch name from a change.
    
    Args:
      change - Change map (see prstack.vcs.graph/node->change)
      
    Returns:
      String or nil, the remote branch name without markers
      
    Schema:
      [:maybe :string]")
  
  (read-graph [this vcs-config]
    "Reads the full VCS graph from trunk to all bookmark heads.
    
    Builds a complete graph representation with parent/child relationships
    for all commits between trunk and bookmarked branches.
    
    Args:
      vcs-config - VCSConfig map (from config method)
      
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
  
  (read-current-stack-graph [this vcs-config]
    "Reads a graph for the current working copy stack.
    
    Includes all changes from trunk to the current working copy, even if
    the current change is not bookmarked.
    
    Args:
      vcs-config - VCSConfig map (from config method)
      
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Default implementation (currently using Jujutsu)

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


