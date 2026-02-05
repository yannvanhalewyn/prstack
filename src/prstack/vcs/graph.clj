(ns prstack.vcs.graph
  "VCS-agnostic graph representation of a commit log and traversal algorithms.
  It uses a DAG with bidirectional edges (parent/child) and metadata about
  branches, trunk, and merge status."
  (:require
    [prstack.change :as change]
    [prstack.tools.schema :as tools.schema]
    [prstack.utils :as u]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data structures

(def Node
  "A node in the commit graph representing a single change/commit."
  (tools.schema/merge
    change/Change
    [:map
     ;; The VCS is responsible for returning the correct parent-ids
     ;; Later we turn it into a graph and pre-compute children-ids for
     ;; bi-directional navigation.
     [:change/children-ids [:sequential :string]]]))

(def ^:lsp/allow-unused Graph
  "A directed acyclic graph of commits/changes."
  [:map
   [:graph/nodes [:map-of :string Node]] ;; change-id -> Node
   [:graph/trunk-id :string]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph construction

(defn build-graph
  "Builds a graph from a collection of node maps.

  Args:
    nodes - collection of maps with `:change/change-id`, `:change/parent-ids`, etc.
    trunk-id - change-id of the trunk node

  Returns:
    Graph map with bidirectional edges computed."
  [nodes trunk-id]
  (let [parent->children (reduce
                           (fn [acc node]
                             (reduce
                               (fn [acc parent-id]
                                 (update acc parent-id (fnil conj []) (:change/change-id node)))
                               acc
                               (:change/parent-ids node)))
                           {}
                           nodes)
        nodes-map (into {}
                    (map (fn [node]
                           (let [change-id (:change/change-id node)
                                 trunk-node? (= change-id trunk-id)]
                             [change-id
                              (cond-> (assoc node
                                        :change/children-ids (or (parent->children change-id) [])
                                        :change/trunk-node? trunk-node?))])))
                    nodes)]
    {:graph/nodes nodes-map
     :graph/trunk-id trunk-id}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph queries

(defn get-node
  "Returns the node for the given change-id, or nil if not found."
  [vcs-graph change-id]
  (get-in vcs-graph [:graph/nodes change-id]))

(defn all-nodes [vcs-graph]
  (vals (:graph/nodes vcs-graph)))

(defn find-nodes [vcs-graph pred]
  (filter pred (all-nodes vcs-graph)))

(defn all-selected-branchnames [vcs-graph]
  (keep :change/selected-branchname (all-nodes vcs-graph)))

(defn bookmarked-leaf-nodes
  "Returns all leaf nodes that have local bookmarks.
  These are the 'real' leaves that represent feature branches."
  [bookmarks-graph]
  (->> (all-nodes bookmarks-graph)
    (remove (comp seq :change/children-ids))))

(declare remove-node)

(defn bookmarks-subgraph
  "Returns a subgraph with only bookmarks. This is useful for performing
  further analysis on the bookmarks structure.
  Using the original vcs graph and filtering it ensures existing paths are
  maintained"
  [vcs-graph]
  (let [ids-without-bookmarks (->> (all-nodes vcs-graph)
                                (remove :change/selected-branchname)
                                (map :change/change-id))]
    (reduce remove-node vcs-graph ids-without-bookmarks)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph manipulation

;; This is not the most efficient way to do this because we want to remove many
;; nodes and keep only a few, causing many intermittent ref updates. But it's
;; the simplest way to reason about for now.
(defn- remove-node
  "Removes a node from the graph. This will:
  - erase it from the nodes list
  - clear references to it from it's parents and children
  - connect it's parents an children to eachother"
  [vcs-graph node-id]
  (let [node (get-node vcs-graph node-id)
        update-refs
        (fn [graph {:keys [target-ids relation-key remove-id add-ids]}]
          (reduce
            (fn [graph* parent-id]
              (update-in graph* [:graph/nodes parent-id relation-key]
                #(->> %
                   (remove #{remove-id})
                   (concat add-ids))))
            graph
            target-ids)) ]
    (-> vcs-graph
      (u/dissoc-in [:graph/nodes node-id])
      (update-refs
        {:target-ids (:change/parent-ids node)
         :relation-key :change/children-ids
         :remove-id node-id
         :add-ids (:change/children-ids node)})
      (update-refs
        {:target-ids (:change/children-ids node)
         :relation-key :change/parent-ids
         :remove-id node-id
         :add-ids (:change/parent-ids node)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph traversal

(defn find-all-paths-to-trunk
  "Finds all paths from node-id to trunk, following all parent edges.

  For merge nodes, explores all parent paths (produces multiple paths).

  Args:
    vcs-graph - the graph to search
    node-id - the node to start from
    trunk-id - (optional) the trunk node to find paths to. If not provided,
               uses the trunk-id from the graph.

  Returns:
    A vector of paths, where each path is [node-id ... trunk-id]."
  ([vcs-graph node-id]
   (find-all-paths-to-trunk vcs-graph node-id (:graph/trunk-id vcs-graph)))
  ([vcs-graph node-id trunk-id]
   (letfn [(dfs [current-id path]
             (cond
               ;; Found trunk
               (= current-id trunk-id) [(conj path current-id)]
               (nil? current-id) []
               :else
               (let [node (get-node vcs-graph current-id)
                     parents (:change/parent-ids node)]
                 (if (empty? parents)
                   []
                   (mapcat #(dfs % (conj path current-id))
                     parents)))))]
     (dfs node-id []))))

(defn is-ancestor?
  "Checks if ancestor-id is an ancestor of descendant-id in the graph.
  Traverses parent edges from descendant looking for ancestor.

  Args:
    vcs-graph - the graph to search
    ancestor-id - change-id of the potential ancestor
    descendant-id - change-id of the potential descendant

  Returns:
    Boolean, true if ancestor-id is reachable by following parent edges from descendant-id"
  [vcs-graph ancestor-id descendant-id]
  (if (= ancestor-id descendant-id)
    true
    (loop [to-visit #{descendant-id}
           visited #{}]
      (if (empty? to-visit)
        false
        (let [current-id (first to-visit)
              rest-to-visit (disj to-visit current-id)]
          (cond
            (= current-id ancestor-id) true
            (visited current-id) (recur rest-to-visit visited)
            :else
            (let [node (get-node vcs-graph current-id)
                  parents (set (:change/parent-ids node))]
              (recur (into rest-to-visit (remove visited parents))
                (conj visited current-id)))))))))

(comment
  (def test-graph
    (build-graph
      [{:change/change-id "trunk"
        :change/parent-ids []
        :change/local-branchnames ["main"]
        :change/remote-branchnames ["main@origin"]}
       {:change/change-id "feature-1"
        :change/parent-ids ["trunk"]
        :change/local-branchnames ["feature-1"]
        :change/remote-branchnames []}
       {:change/change-id "feature-2"
        :change/parent-ids ["feature-1"]
        :change/local-branchnames ["feature-2"]
        :change/remote-branchnames []}
       {:change/change-id "feature-parallel"
        :change/parent-ids ["trunk"]
        :change/local-branchnames ["feature-parallel"]
        :change/remote-branchnames []}
       {:change/change-id "megamerge"
        :change/parent-ids ["feature-parallel" "feature-2"]
        :change/local-branchnames []
        :change/remote-branchnames []} ]
      "trunk"))
  (find-all-paths-to-trunk test-graph "feature-2")
  (find-all-paths-to-trunk test-graph "megamerge")

  )
