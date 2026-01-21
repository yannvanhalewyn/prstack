(ns prstack.stack
  (:require
    [prstack.vcs :as vcs]
    [prstack.vcs.graph :as graph]))

(def Change
  [:map
   [:change/description :string]
   [:change/change-id :string]
   [:change/commit-sha :string]
   [:change/local-branches [:sequential :string]]
   [:change/remote-branches [:sequential :string]]])

(def ^:lsp/allow-unused Stack
  ;; Every leaf is a change. The stack of changes is ordered from
  ;; trunk change to each leaf
  [:sequential Change])

(defn- should-ignore-leaf?
  "Returns true if the leaf node should be ignored based on config."
  [{:keys [ignored-branches]} {:vcs-config/keys [trunk-branch]} node]
  (let [branch-name (vcs/local-branchname (graph/node->change node))]
    (or (= branch-name trunk-branch)
        (contains? ignored-branches branch-name))))

(defn- node->stack
  "Converts a leaf node to a stack by finding the path to trunk."
  [vcs-graph node]
  (when-let [path (graph/find-path-to-trunk vcs-graph (:node/change-id node))]
    (graph/path->stack vcs-graph path)))

(defn- nodes->stacks
  "Converts leaf nodes to stacks, filtering out ignored branches."
  [config vcs-config vcs-graph nodes]
  (into []
    (comp
      (remove #(should-ignore-leaf? config vcs-config %))
      (keep #(node->stack vcs-graph %)))
    nodes))

(defn get-all-stacks
  "Returns all stacks in the repository from a graph."
  [vcs-config config]
  (let [vcs-graph (vcs/read-graph vcs-config)
        leaves (graph/bookmarked-leaf-nodes vcs-graph)]
    (nodes->stacks config vcs-config vcs-graph leaves)))

(defn get-current-stacks
  "Returns the stack(s) containing the current working copy.
  
  If the current change is a megamerge (multiple parents), returns multiple
  stacks - one for each parent path. Otherwise, returns a single stack."
  [vcs-config config]
  (let [vcs-graph (vcs/read-graph vcs-config)
        current-id (vcs/current-change-id)
        current-node (graph/get-node vcs-graph current-id)]
    (if-let [megamerge (graph/find-megamerge-in-path vcs-graph current-id)]
      ;; Handle megamerge: get all paths from megamerge to trunk
      (let [paths (graph/find-all-paths-to-trunk vcs-graph (:node/change-id megamerge))
            stacks (mapv #(graph/path->stack vcs-graph %) paths)]
        (into []
          (comp
            (remove empty?)
            (remove (fn [stack]
                      (should-ignore-leaf? config vcs-config
                        (graph/get-node vcs-graph
                          (:change/change-id (last stack)))))))
          stacks))
      ;; Single path
      (when-let [path (graph/find-path-to-trunk vcs-graph current-id)]
        [(graph/path->stack vcs-graph path)]))))

(defn get-stack
  "Returns a single stack for the given ref."
  [ref vcs-config]
  (let [vcs-graph (vcs/read-graph vcs-config)
        ;; For now, assume ref is a branch name and find the node with that branch
        node (some (fn [[_id node]]
                     (when (some #{ref} (:node/local-branches node))
                       node))
                   (:graph/nodes vcs-graph))]
    (when node
      (node->stack vcs-graph node))))

(defn reverse-stacks
  "Reverses the order of the changes in every stack. Stacks are represented in
  ascending order, from trunk to each change. When rendering in the UI, we want
  to display them in descending order, with latest change at the top and trunk
  at the bottom."
  [stacks]
  (mapv (comp vec reverse) stacks))

(defn leaves [stacks]
  (apply concat stacks))

(comment
  (get-current-stacks {:vcs-config/trunk-branch "main"} {})
  (get-stack "test-branch" {:vcs-config/trunk-branch "main"})
  (get-all-stacks
    {:vcs-config/trunk-branch "main"}
    {:ignored-branches #{}}))
