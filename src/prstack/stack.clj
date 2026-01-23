(ns prstack.stack
  (:require
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

(defn parse-change
  "Converts a Node to the legacy Change format used by the rest of the codebase.

  Options:
    :trunk-branch - String, name of trunk branch (for :trunk type detection)
    :feature-base-branches - Set of strings, names of feature base branches"
  ([change]
   (parse-change change {}))
  ([change {:keys [ignored-branches feature-base-branches]}]
   (let [local-branch (first (:change/local-branchnames change))
         selected-branch (first (remove ignored-branches (:change/local-branchnames change)))
         bookmark-type
         (cond
           (:change/trunk-node? change) :trunk
           (and local-branch (contains? feature-base-branches local-branch)) :feature-base
           :else :regular)]
     (cond-> (assoc change :change/bookmark-type bookmark-type)
      selected-branch (assoc :change/selected-branchname selected-branch)))))

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
   (reverse
     (into []
       (comp
         (map #(vcs.graph/get-node graph %))
         (map #(parse-change % config))
         (filter :change/selected-branchname))
       path))))

;; Public

(defn- should-ignore-node?
  "Returns true if the leaf node should be ignored based on config. It will be
  ignored when it's either a node on the trunk branch"
  [node]
  (or (:change/trunk-node? node)
      (not (:change/selected-branchname node))))

(defn- node->stack
  "Converts a leaf node to a stack by finding the path to trunk."
  [node vcs-graph config]
  (when-let [path (vcs.graph/find-path-to-trunk vcs-graph (:change/change-id node))]
    (path->stack vcs-graph path config)))

(comment
  (def vcs-graph (vcs/read-graph vcs-))
  (into []
    (comp
      (keep #(node->stack % vcs-graph config-))
      (remove (fn [stack]
                (should-ignore-node? (last stack)))))
    (vcs.graph/bookmarked-leaf-nodes (vcs/read-graph vcs-))))

(defn get-all-stacks
  "Returns all stacks in the repository from a graph."
  [vcs config]
  (let [vcs-graph (vcs/read-graph vcs)
        leaves (vcs.graph/bookmarked-leaf-nodes vcs-graph)]
    (into []
      (comp
        (keep #(node->stack % vcs-graph config))
        (remove (fn [stack]
                  (should-ignore-node? (last stack)))))
      leaves)))

(comment
  (vcs.graph/find-all-paths-to-trunk vcs-graph "vyonxkyqxnns")

  (into []
    (comp
      (map #(path->stack vcs-graph % config-))
      (remove
        (fn [stack]
          (should-ignore-node? (last stack)))))
    (vcs.graph/find-all-paths-to-trunk vcs-graph "vyonxkyqxnns")))

(defn get-current-stacks
  "Returns the stack(s) containing the current working copy.

  If the current change is a megamerge (multiple parents), returns multiple
  stacks - one for each parent path. Otherwise, returns a single stack."
  [vcs config]
  (let [vcs-graph (vcs/read-current-stack-graph vcs)
        current-id (vcs/current-change-id vcs)
        paths (vcs.graph/find-all-paths-to-trunk vcs-graph current-id)]
    (into []
      (comp
        (map #(path->stack vcs-graph % config))
        (remove
          (fn [stack]
           (should-ignore-node? (last stack)))))
      paths)))

(defn get-stack
  "Returns a single stack for the given ref."
  [vcs config ref]
  (let [vcs-graph (vcs/read-graph vcs)
        ;; For now, assume ref is a branch name and find the node with that branch
        node (some (fn [[_id node]]
                     (when (some #{ref} (:change/local-branchnames node))
                       node))
                   (:graph/nodes vcs-graph))]
    (when node
      (node->stack config vcs-graph node))))

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
  [change {:keys [feature-base-branches]}]
  (contains? feature-base-branches (:change/selected-branchname change)))

(defn- truncate-stack-at-feature-base
  "Truncates a stack to stop at the first feature base branch.

  If a feature base branch is found, the stack is cut at that point,
  with the feature base branch as the last element (the base).
  Everything before the feature base (including trunk) is removed.

  Returns the truncated stack, or the original stack if no feature base found."
  [config stack]
  (let [feature-base-branches (:feature-base-branches config)]
    (if (empty? feature-base-branches)
      stack
      (let [feature-base-idx (some (fn [idx]
                                     (when (is-feature-base-branch? (nth stack idx) config)
                                       idx))
                                   (range (count stack)))]
        (if feature-base-idx
          ;; Cut the stack from the feature base to the end (including feature base as base)
          (subvec (vec stack) feature-base-idx)
          stack)))))

(comment
  (truncate-stack-at-feature-base
    {:feature-base-branches #{"feature-2-base"}}
    [{:change/description "Main Change"
      :change/selected-branchname "main"}
     {:change/description "Feature 2 merge base"
      :change/selected-branchname "feature-2-base"}
     {:change/description "Feature 2 part 1"
      :change/selected-branchname "feature-2-part-1"}
     {:change/description "Feature 2 part 2"
      :change/selected-branchname "feature-2-part-2"}]))

(defn get-feature-base-stacks
  "Returns stacks for all feature base branches onto trunk.

  Each stack contains [trunk-branch feature-base-branch].
  Only includes feature bases that are direct descendants of trunk
  (i.e., the stack has length 2)."
  [vcs config]
  (let [vcs-graph (vcs/read-graph vcs)
        trunk-branch (vcs/trunk-branch vcs)]
    (into []
      (keep (fn [branch-name]
              (let [node (some (fn [[_id node]]
                                 (when (some #{branch-name} (:change/local-branchnames node))
                                   node))
                               (:graph/nodes vcs-graph))]
                (when node
                  (let [stack (node->stack node vcs-graph config)]
                    ;; Only return if:
                    ;; 1. Stack exists
                    ;; 2. Starts with trunk
                    ;; 3. Has exactly 2 elements (trunk + feature-base)
                    (when (and stack
                               (= 2 (count stack))
                               (= trunk-branch (:change/selected-branchname vcs (first stack))))
                      stack))))))
      (:feature-base-branches config))))

(defn process-stacks-with-feature-bases
  "Processes stacks to handle feature base branches.

  Returns a map with:
    :regular-stacks - stacks truncated at feature base branches
    :feature-base-stacks - stacks from trunk to each feature base branch"
  [vcs config stacks]
  (let [feature-base-stacks (get-feature-base-stacks vcs config)
        regular-stacks (mapv #(truncate-stack-at-feature-base config %) stacks)]
    {:regular-stacks regular-stacks
     :feature-base-stacks feature-base-stacks}))

(comment
  (require '[prstack.config :as config])
  (def config- (config/read-local))
  (def vcs- (vcs/make config-))
  (tap> (get-all-stacks vcs- config-))
  (tap> (get-current-stacks vcs- config-))
  (tap> (get-stack vcs- config- "test-branch"))
  (get-all-stacks vcs- {:ignored-branches #{}})
  (tap>
   (process-stacks-with-feature-bases vcs- config-
     (get-all-stacks vcs- config-))))
