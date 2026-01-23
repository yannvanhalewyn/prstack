(ns prstack.vcs.graph
  "VCS-agnostic graph representation of a commit log and traversal algorithms.
  It uses a DAG with bidirectional edges (parent/child) and metadata about
  branches, trunk, and merge status.")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data structures

(def Node
  "A node in the commit graph representing a single change/commit."
  [:map
   [:change/change-id :string]
   [:change/commit-sha {:optional true} :string]
   [:change/parent-ids [:sequential :string]]
   [:change/children-ids [:sequential :string]]
   [:change/local-branchnames [:sequential :string]]
   [:change/remote-branchnames [:sequential :string]]
   [:change/trunk-node? :boolean]
   [:change/merge-node? :boolean]])

(def ^:lsp/allow-unused Graph
  "A directed acyclic graph of commits/changes."
  [:map
   [:graph/nodes [:map-of :string Node]] ;; change-id -> Node
   [:graph/trunk-id :string]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph construction

(defn- compute-children
  "Given nodes map, computes and adds `:change/children-ids` to each node based
  on parent edges."
  [nodes]
  (let [;; Build child->parents map
        children (reduce
                   (fn [acc [node-id node]]
                     (reduce
                       (fn [acc parent-id]
                         (update acc parent-id (fnil conj []) node-id))
                       acc
                       (:change/parent-ids node)))
                   {}
                   nodes)]
    ;; Add children to nodes
    (reduce-kv
      (fn [nodes node-id child-ids]
        (assoc-in nodes [node-id :change/children-ids] child-ids))
      nodes
      children)))

(defn build-graph
  "Builds a graph from a collection of node maps.

  Args:
    nodes - collection of maps with `:change/change-id`, `:change/parent-ids`, etc.
    trunk-id - change-id of the trunk node

  Returns:
    Graph map with bidirectional edges computed."
  [nodes trunk-id]
  (let [;; Create nodes map keyed by change-id
        nodes-map (into {}
                    (map (fn [node]
                           [(:change/change-id node)
                            (assoc node
                              :change/children-ids []
                              :change/trunk-node? (= (:change/change-id node) trunk-id)
                              :change/merge-node? (> (count (:change/parent-ids node)) 1))]))
                    nodes)
        ;; Compute children from parent edges
        nodes-map (compute-children nodes-map)]
    {:graph/nodes nodes-map
     :graph/trunk-id trunk-id}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph queries

(defn get-node
  "Returns the node for the given change-id, or nil if not found."
  [vcs-graph change-id]
  (get-in vcs-graph [:graph/nodes change-id]))

(defn bookmarked-leaf-nodes
  "Returns all leaf nodes that have local bookmarks.
  These are the 'real' leaves that represent feature branches."
  [vcs-graph]
  (into []
    (comp
      (filter (fn [[_id node]]
                (and (empty? (:change/children-ids node))
                     (:change/selected-branchname node))))
      (map second))
    (:graph/nodes vcs-graph)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph traversal

(defn find-path-to-trunk
  "Finds a path from node-id to trunk, following parent edges.

  Returns:
    A vector of change-ids [node-id ... trunk-id], or nil if no path exists.

  For merge nodes with multiple parents, follows the first parent."
  [vcs-graph node-id]
  (let [trunk-id (:graph/trunk-id vcs-graph)]
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
        (let [node (get-node vcs-graph current-id)
              parent-id (first (:change/parent-ids node))]
          (recur (conj path current-id)
                 parent-id
                 (conj visited current-id)))))))

(defn find-all-paths-to-trunk
  "Finds all paths from node-id to trunk, following all parent edges.

  For merge nodes, explores all parent paths (produces multiple paths).

  Returns:
    A vector of paths, where each path is [node-id ... trunk-id]."
  [vcs-graph node-id]
  (let [trunk-id (:graph/trunk-id vcs-graph)]
    (letfn [(dfs [current-id path visited]
              (cond
                ;; Found trunk
                (= current-id trunk-id)
                [(conj path current-id)]

                ;; Cycle or dead end
                (or (contains? visited current-id) (nil? current-id))
                []

                :else
                (let [node (get-node vcs-graph current-id)
                      parents (:change/parent-ids node)]
                  (if (empty? parents)
                    []
                    (mapcat #(dfs % (conj path current-id) (conj visited current-id))
                            parents)))))]
      (dfs node-id [] #{}))))

(comment
  ;; Example usage
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
        :change/remote-branchnames []}]
      "trunk"))
  (find-path-to-trunk test-graph "feature-2"))
