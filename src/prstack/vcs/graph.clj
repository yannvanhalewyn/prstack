(ns prstack.vcs.graph
  "VCS-agnostic graph representation and traversal algorithms.

  A graph represents the commit DAG with bidirectional edges (parent/child)
  and metadata about branches, trunk, and merge status.")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data structures

(def Node
  "A node in the commit graph representing a single change/commit."
  [:map
   [:node/change-id :string]
   [:node/commit-sha {:optional true} :string]
   [:node/parents [:sequential :string]]  ; parent change-ids
   [:node/children [:sequential :string]] ; child change-ids (computed)
   [:node/local-branches [:sequential :string]]
   [:node/remote-branches [:sequential :string]]
   [:node/is-trunk? :boolean]
   [:node/is-merge? :boolean]])

(def ^:lsp/allow-unused Graph
  "A directed acyclic graph of commits/changes."
  [:map
   [:graph/nodes [:map-of :string Node]] ; change-id -> Node
   [:graph/trunk-id :string]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph construction

(defn- compute-children
  "Given nodes map, computes and adds :node/children to each node based on parent edges."
  [nodes]
  (let [;; Build child->parents map
        children (reduce
                   (fn [acc [node-id node]]
                     (reduce
                       (fn [acc parent-id]
                         (update acc parent-id (fnil conj []) node-id))
                       acc
                       (:node/parents node)))
                   {}
                   nodes)]
    ;; Add children to nodes
    (reduce-kv
      (fn [nodes node-id child-ids]
        (assoc-in nodes [node-id :node/children] child-ids))
      nodes
      children)))

(defn build-graph
  "Builds a graph from a collection of node maps.

  Args:
    nodes - collection of maps with :node/change-id, :node/parents, etc.
    trunk-id - change-id of the trunk node

  Returns:
    Graph map with bidirectional edges computed."
  [nodes trunk-id]
  (let [;; Create nodes map keyed by change-id
        nodes-map (into {}
                    (map (fn [node]
                           [(:node/change-id node)
                            (assoc node
                              :node/children []
                              :node/is-trunk? (= (:node/change-id node) trunk-id)
                              :node/is-merge? (> (count (:node/parents node)) 1))]))
                    nodes)
        ;; Compute children from parent edges
        nodes-map (compute-children nodes-map)]
    {:graph/nodes nodes-map
     :graph/trunk-id trunk-id}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph queries

(defn get-node
  "Returns the node for the given change-id, or nil if not found."
  [graph change-id]
  (get-in graph [:graph/nodes change-id]))

(defn leaf-nodes
  "Returns all leaf nodes (nodes with no children) in the graph."
  [graph]
  (into []
    (comp
      (filter (fn [[_id node]] (empty? (:node/children node))))
      (map second))
    (:graph/nodes graph)))

(defn bookmarked-leaf-nodes
  "Returns all leaf nodes that have local bookmarks.
  These are the 'real' leaves that represent feature branches."
  [graph]
  (into []
    (comp
      (filter (fn [[_id node]]
                (and (empty? (:node/children node))
                     (seq (:node/local-branches node)))))
      (map second))
    (:graph/nodes graph)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph traversal

(defn find-path-to-trunk
  "Finds a path from node-id to trunk, following parent edges.

  Returns:
    A vector of change-ids [node-id ... trunk-id], or nil if no path exists.

  For merge nodes with multiple parents, follows the first parent."
  [graph node-id]
  (let [trunk-id (:graph/trunk-id graph)]
    (loop [path []
           current-id node-id
           visited #{}]
      (cond
        ;; Found trunk
        (= current-id trunk-id)
        (conj path current-id)

        ;; Cycle detection
        (contains? visited current-id)
        nil

        ;; Dead end
        (nil? current-id)
        nil

        :else
        (let [node (get-node graph current-id)
              parent-id (first (:node/parents node))]
          (recur (conj path current-id)
                 parent-id
                 (conj visited current-id)))))))

(defn find-all-paths-to-trunk
  "Finds all paths from node-id to trunk, following all parent edges.

  For merge nodes, explores all parent paths (produces multiple paths).

  Returns:
    A vector of paths, where each path is [node-id ... trunk-id]."
  [graph node-id]
  (let [trunk-id (:graph/trunk-id graph)]
    (letfn [(dfs [current-id path visited]
              (cond
                ;; Found trunk
                (= current-id trunk-id)
                [(conj path current-id)]

                ;; Cycle or dead end
                (or (contains? visited current-id) (nil? current-id))
                []

                :else
                (let [node (get-node graph current-id)
                      parents (:node/parents node)]
                  (if (empty? parents)
                    []
                    (mapcat #(dfs % (conj path current-id) (conj visited current-id))
                            parents)))))]
      (dfs node-id [] #{}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Conversion to legacy Change format

(defn node->change
  "Converts a Node to the legacy Change format used by the rest of the codebase.

  Options:
    :trunk-branch - String, name of trunk branch (for :trunk type detection)
    :feature-base-branches - Set of strings, names of feature base branches"
  ([node]
   (node->change node {}))
  ([node {:keys [ignored-branches feature-base-branches]}]
   (let [local-branch (first (:node/local-branches node))
         bookmark-type (cond
                         (:node/is-trunk? node) :trunk
                         (and local-branch (contains? feature-base-branches local-branch)) :feature-base
                         :else :regular)
         selected-branch (first (remove ignored-branches (:node/local-branches node)))]
     (cond-> {:change/change-id (:node/change-id node)
              :change/local-branches (:node/local-branches node)
              :change/remote-branches (:node/remote-branches node)
              :change/bookmark-type bookmark-type}
       (:node/commit-sha node)
       (assoc :change/commit-sha (:node/commit-sha node))
       selected-branch
       (assoc :change/selected-branch selected-branch)))))

(defn- node-has-bookmarks?
  "Returns true if the node has at least one local bookmark."
  [node]
  (seq (:node/local-branches node)))

(defn path->stack
  "Converts a path (vector of change-ids) to a Stack (vector of Changes).

  Only includes nodes that have bookmarks (local branches). This filters out
  intermediate unbookmarked commits.

  Args:
    graph - the graph containing the nodes
    path - vector of change-ids [leaf ... trunk]
    opts - options map with :trunk-branch and :feature-base-branches

  Returns:
    Vector of Change maps ordered from trunk to leaf, containing only bookmarked nodes."
  ([graph path]
   (path->stack graph path {}))
  ([graph path config]
   (->> path
        (map #(get-node graph %))
        (filter node-has-bookmarks?)
        (map #(node->change % config))
        (reverse)
        (into []))))

(comment
  ;; Example usage
  (def test-graph
    (build-graph
      [{:node/change-id "trunk"
        :node/parents []
        :node/local-branches ["main"]
        :node/remote-branches ["main@origin"]}
       {:node/change-id "feature-1"
        :node/parents ["trunk"]
        :node/local-branches ["feature-1"]
        :node/remote-branches []}
       {:node/change-id "feature-2"
        :node/parents ["feature-1"]
        :node/local-branches ["feature-2"]
        :node/remote-branches []}]
      "trunk"))

  (leaf-nodes test-graph)
  (find-path-to-trunk test-graph "feature-2")
  (path->stack test-graph (find-path-to-trunk test-graph "feature-2")))
