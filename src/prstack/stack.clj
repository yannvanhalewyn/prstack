(ns prstack.stack
  (:require
    [prstack.change :as change]
    [prstack.tools.schema :as tools.schema]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]
    [prstack.vcs.graph :as vcs.graph]))

;; Changes in stacks can have a bit more information associated than just
;; the change as outputted by the VCS
(def Change
  (tools.schema/merge
    change/Change
    [:map
     ;; Changes can have multiple bookmarks. This is to preselect one of them
     ;; respecting ignored branches.
     [:change/selected-branchname :string]]))

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
  "Converts a leaf node to a stack by finding the path to trunk.

  Optionally accepts a fork-point-id to use as the trunk anchor for this
  specific stack. This is useful when trunk has advanced and the stack is
  forked from an older trunk commit."
  ([node vcs-graph]
   (node->stacks node vcs-graph nil))
  ([node vcs-graph fork-point-id]
   (let [paths (if fork-point-id
                 (vcs.graph/find-all-paths-to-trunk vcs-graph (:change/change-id node) fork-point-id)
                 (vcs.graph/find-all-paths-to-trunk vcs-graph (:change/change-id node)))]
     (map #(path->stack % vcs-graph) paths))))

(defn get-all-stacks
  "Returns all stacks in the repository from a graph."
  [{:system/keys [vcs user-config]}]
  (let [vcs-graph (vcs/read-graph vcs user-config)
        bookmarks-graph (vcs.graph/bookmarks-subgraph vcs-graph)
        leaves (vcs.graph/bookmarked-leaf-nodes bookmarks-graph)]
    ;; DEBUG: Print leaves to understand the issue
    (println "DEBUG: leaves count:" (count leaves))
    (doseq [leaf leaves]
      (println "DEBUG: leaf change-id:" (pr-str (:change/change-id leaf))
               "selected-branch:" (pr-str (:change/selected-branchname leaf))))
    (mapcat
      (fn [leaf]
        ;; Find the fork point for this leaf to handle cases where trunk has
        ;; advanced
        (let [change-id (:change/change-id leaf)]
          (when (seq change-id)  ;; Guard against empty change-id
            (node->stacks leaf vcs-graph
              (vcs/find-fork-point vcs change-id)))))
      leaves)))

(defn get-current-stacks
  "Returns the stack(s) containing the current working copy.

  If the current change is a megamerge (multiple parents), returns multiple
  stacks - one for each parent path. Otherwise, returns a single stack."
  [system]
  (let [vcs (:system/vcs system)
        vcs-graph (vcs/read-current-stack-graph system)
        current-id (vcs/current-change-id vcs)
        ;; Find the fork point for the current change to handle advanced trunk
        fork-point-id (vcs/find-fork-point vcs "@")
        paths (vcs.graph/find-all-paths-to-trunk vcs-graph current-id fork-point-id)]
    (keep #(path->stack % vcs-graph) paths)))

(defn has-segments? [stack]
  ;; When a stack has only the trunk node
  (> (count stack) 1))

(defn any-segments? [stacks]
  (some has-segments? stacks))

(comment
  (def sys-
    (prstack.system/new
      (prstack.config/read-global)
      (assoc (prstack.config/read-local)
        :vcs :jujutsu #_:git)
      {:project-dir "./tmp/parallel-branches"}))
  (def vcs- (:system/vcs sys-))

  (def vcs-graph- (vcs/read-current-stack-graph sys-))
  (def bookmarks-graph- (vcs.graph/bookmarks-subgraph vcs-graph-))
  (def current-id- (vcs/current-change-id vcs-))
  (def paths- (vcs.graph/find-all-paths-to-trunk bookmarks-graph- current-id-))

  (def leaf-nodes- (vcs.graph/bookmarked-leaf-nodes bookmarks-graph-))
  (node->stacks leaf-nodes- vcs-graph-))

(defn get-stacks
  "Returns a single stack for the given ref."
  [{:system/keys [user-config vcs]} ref]
  (let [vcs-graph (vcs/read-graph vcs user-config)
        ;; For now, assume ref is a branch name and find the node with that branch
        node (some (fn [[_id node]]
                     (when (some #{ref} (:change/local-branchnames node))
                       node))
               (:graph/nodes vcs-graph))]
    (when node
      (let [fork-point-id (vcs/find-fork-point vcs ref)]
        (node->stacks node vcs-graph fork-point-id)))))

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

(defn- stack-branch-signature
  "Returns a unique signature for a stack based on its branch names.
  Used for deduplication."
  [stack]
  (mapv :change/selected-branchname stack))

(defn- feature-base-branch-name
  "Returns the feature-base branch name from a feature-base stack.
  The feature-base is the last element (top of stack before reversal)."
  [stack]
  (:change/selected-branchname (last stack)))

(defn split-feature-base-stacks
  "Takes a list of stacks and truncates them starting at the feature base
  branches instead of the trunk. Returns a seperate list of feature base
  stacks, which are size two stacks of the feature base and it's parent
  bookmarked change.

  Returns a map with:
    :regular-stacks - stacks truncated at feature base branches
    :feature-base-stacks - stacks from trunk to each feature base branch"
  [stacks]
  (let [partitioned-stacks (map partition-at-feature-base stacks)
        feature-base-stacks (keep first partitioned-stacks)
        regular-stacks (map second partitioned-stacks)
        ;; Deduplicate feature-base stacks by the feature-base branch name,
        ;; keeping the shortest path (fewest intermediate branches).
        ;; This handles cases where merge commits create multiple paths.
        unique-feature-base-stacks
        (->> feature-base-stacks
          (group-by feature-base-branch-name)
          vals
          (map #(apply min-key count %)))
        ;; Deduplicate regular stacks by branch signature.
        ;; After partitioning, different paths through the graph may result
        ;; in the same regular stack (same branches from feature-base to leaf).
        unique-regular-stacks
        (vals (into {} (map (juxt stack-branch-signature identity) regular-stacks)))]
    {:regular-stacks unique-regular-stacks
     :feature-base-stacks unique-feature-base-stacks}))

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
