(ns prstack.stack
  (:require
    [prstack.utils :as u]
    [prstack.vcs :as vcs]
    [prstack.vcs.graph :as vcs.graph]))

(def Change
  [:map
   [:change/description :string]
   [:change/change-id :string]
   [:change/commit-sha :string]
   [:change/local-branchnames [:sequential :string]]
   [:change/remote-branchnames [:sequential :string]]
   ;; Changes can have multiple bookmarks. This is to preselect one of them
   ;; respecting ignored branches.
   [:change/selected-branchname :string]])

(def ^:lsp/allow-unused Stack
  ;; Every leaf is a change. The stack of changes is ordered from
  ;; trunk change to each leaf
  [:sequential Change])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parsing graph nodes

(defn path->stack
  "Converts a path (vector of change-ids) to a Stack (vector of Changes).

  Only includes nodes that have a selected bookmark. This effecively filters
  out intermediate unbookmarked commits or ignored bookmarks.

  Args:
    graph - the graph containing the nodes
    path - vector of change-ids [leaf ... trunk]
    opts - options map with :trunk-branch and :feature-base-branches

  Returns:
    Vector of Change maps ordered from trunk to leaf, containing only bookmarked nodes."
  [path vcs-graph]
  (reverse
    (into []
      (comp
        (map #(vcs.graph/get-node vcs-graph %))
        (filter :change/selected-branchname))
      path)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public

(defn- node->stacks
  "Converts a leaf node to a stack by finding the path to trunk."
  [node vcs-graph]
  (->> (vcs.graph/find-all-paths-to-trunk vcs-graph (:change/change-id node))
    (map #(path->stack % vcs-graph))))

(defn get-all-stacks
  "Returns all stacks in the repository from a graph."
  [vcs config]
  (let [vcs-graph (vcs/read-graph vcs config)
        bookmarks-graph (vcs.graph/bookmarks-subgraph vcs-graph)
        ;; We did this work to get the bookmarks graph but I could've just
        ;; found all the leaves and worked from there.
        leaves (vcs.graph/bookmarked-leaf-nodes bookmarks-graph)]
    (mapcat #(node->stacks % vcs-graph) leaves)))

(defn get-current-stacks
  "Returns the stack(s) containing the current working copy.

  If the current change is a megamerge (multiple parents), returns multiple
  stacks - one for each parent path. Otherwise, returns a single stack."
  [vcs config]
  (let [vcs-graph (vcs/read-current-stack-graph vcs config)
        ;; Need to move up the graph to find the first bookmarks
        ;; Might as well just get the stack directly?
        current-id (vcs/current-change-id vcs)
        paths (vcs.graph/find-all-paths-to-trunk vcs-graph current-id)]
    (keep #(path->stack % vcs-graph) paths)))

(comment
  (def config- (assoc (prstack.config/read-local) :vcs :jujutsu #_:git))
  (def vcs- (vcs/make config-))
  (def vcs-graph- (vcs/read-current-stack-graph vcs- config-))
  (def bookmarks-graph- (vcs.graph/bookmarks-subgraph vcs-graph-))
  (def current-id- (vcs/current-change-id vcs-))
  (def paths- (vcs.graph/find-all-paths-to-trunk bookmarks-graph- current-id-))
  (vcs.graph/bookmarked-leaf-nodes bookmarks-graph-)
  (node->stacks (first (vcs.graph/bookmarked-leaf-nodes bookmarks-graph-))
    vcs-graph-)
  )

(defn get-stacks
  "Returns a single stack for the given ref."
  [vcs config ref]
  (let [vcs-graph (vcs/read-graph vcs config)
        ;; For now, assume ref is a branch name and find the node with that branch
        node (some (fn [[_id node]]
                     (when (some #{ref} (:change/local-branchnames node))
                       node))
               (:graph/nodes vcs-graph))]
    (when node
      (node->stacks node vcs-graph))))

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

(defn- partition-at-feature-base
  "Partitions a stack into two parts, one from the trunk to the feature-base,
  and another from the feature-base onward.

  If no feature base branch is found, the first element will be nil."
  [stack]
  (let [feature-base-idx (u/find-index #(= (:change/type %) :feature-base) stack)]
    (if feature-base-idx
      ;; Cut the stack from the feature base to the end (including feature base as base)
      [(subvec (vec stack) 0 (inc feature-base-idx))
       (subvec (vec stack) feature-base-idx)]
      [nil stack])))

(defn split-feature-base-stacks
  "Takes a list of stacks and truncates them starting at the feature base
  branches instead of the trunk. Returns a seperate list of feature base
  stacks, which are size two stacks of the feature base and it's parent
  bookmarked change.

  Returns a map with:
    :regular-stacks - stacks truncated at feature base branches
    :feature-base-stacks - stacks from trunk to each feature base branch"
  [stacks]
  (let [partitioned-stacks (map partition-at-feature-base stacks)]
    {:regular-stacks (map second partitioned-stacks)
     :feature-base-stacks (distinct (keep first partitioned-stacks))}))

(comment
  (def stacks
    [[{:change/type :trunk}
      {:change/type :feature-base}
      {:change/type :regular}]
     [{:change/type :trunk}
      {:change/type :feature-base}
      {:change/type :regular}]])
  (partition-at-feature-base (first stacks))
  (split-feature-base-stacks stacks))
