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
  (let [vcs-graph (vcs/read-current-stack-graph vcs-config)
        current-id (vcs/current-change-id)]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Feature base branch handling

(defn- is-feature-base-branch?
  "Returns true if the change is a feature base branch."
  [{:keys [feature-base-branches]} change]
  (contains? feature-base-branches (vcs/local-branchname change)))

(defn- truncate-stack-at-feature-base
  "Truncates a stack to stop at the first feature base branch.
  
  If a feature base branch is found, the stack is cut at that point
  (the feature base branch is included as the base).
  
  Returns the truncated stack, or the original stack if no feature base found."
  [config stack]
  (let [feature-base-branches (:feature-base-branches config)]
    (if (empty? feature-base-branches)
      stack
      (let [feature-base-idx (some (fn [idx]
                                     (when (is-feature-base-branch? config (nth stack idx))
                                       idx))
                                   (range (count stack)))]
        (if feature-base-idx
          ;; Cut the stack from the feature base to the end
          (subvec (vec stack) feature-base-idx)
          stack)))))

(defn get-feature-base-stacks
  "Returns stacks for all feature base branches onto trunk.
  
  Each stack contains [trunk-branch feature-base-branch].
  Only includes feature bases that are direct descendants of trunk
  (i.e., the stack has length 2)."
  [vcs-config config]
  (let [vcs-graph (vcs/read-graph vcs-config)
        feature-base-branches (:feature-base-branches config)
        trunk-branch (:vcs-config/trunk-branch vcs-config)]
    (into []
      (keep (fn [branch-name]
              (let [node (some (fn [[_id node]]
                                 (when (some #{branch-name} (:node/local-branches node))
                                   node))
                               (:graph/nodes vcs-graph))]
                (when node
                  (let [stack (node->stack vcs-graph node)]
                    ;; Only return if:
                    ;; 1. Stack exists
                    ;; 2. Starts with trunk
                    ;; 3. Has exactly 2 elements (trunk + feature-base)
                    (when (and stack
                               (= 2 (count stack))
                               (= trunk-branch (vcs/local-branchname (first stack))))
                      stack))))))
      feature-base-branches)))

(defn process-stacks-with-feature-bases
  "Processes stacks to handle feature base branches.
  
  Returns a map with:
    :regular-stacks - stacks truncated at feature base branches
    :feature-base-stacks - stacks from trunk to each feature base branch"
  [vcs-config config stacks]
  (let [feature-base-stacks (get-feature-base-stacks vcs-config config)
        regular-stacks (mapv #(truncate-stack-at-feature-base config %) stacks)]
    {:regular-stacks regular-stacks
     :feature-base-stacks feature-base-stacks}))

(comment
  (get-current-stacks {:vcs-config/trunk-branch "main"} {})
  (get-stack "test-branch" {:vcs-config/trunk-branch "main"})
  (get-all-stacks
    {:vcs-config/trunk-branch "main"}
    {:ignored-branches #{}}))
