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
   [:change/remote-branches [:sequential :string]]
   ;; Changes can have multiple bookmarks. This is to preselect one of them
   ;; respecting ignored branches.
   [:change/selected-branch :string]])

(def ^:lsp/allow-unused Stack
  ;; Every leaf is a change. The stack of changes is ordered from
  ;; trunk change to each leaf
  [:sequential Change])

(defn- should-ignore-leaf?
  "Returns true if the leaf node should be ignored based on config. It will be
  ignored when it's either a node on the trunk branch"
  [node]
  (or (:node/is-trunk? node)
      (not (:node/selected-branch node))))

(defn- node->stack
  "Converts a leaf node to a stack by finding the path to trunk."
  [node vcs-graph config]
  (when-let [path (graph/find-path-to-trunk vcs-graph (:node/change-id node))]
    (graph/path->stack vcs-graph path config)))

(defn get-all-stacks
  "Returns all stacks in the repository from a graph."
  [vcs config]
  (let [vcs-graph (vcs/read-graph vcs)
        leaves (graph/bookmarked-leaf-nodes vcs-graph)]
    (into []
      (comp
        (remove should-ignore-leaf?)
        (keep #(node->stack % vcs-graph config)))
      leaves)))

(defn get-current-stacks
  "Returns the stack(s) containing the current working copy.

  If the current change is a megamerge (multiple parents), returns multiple
  stacks - one for each parent path. Otherwise, returns a single stack."
  [vcs config]
  (let [vcs-graph (vcs/read-current-stack-graph vcs)
        current-id (vcs/current-change-id vcs)
        paths (graph/find-all-paths-to-trunk vcs-graph current-id)]
    (into []
      (comp
        (map #(graph/path->stack vcs-graph % config))
        (remove empty?)
        (map #(graph/get-node vcs-graph (:change/change-id (last %))))
        (remove should-ignore-leaf?))
      paths)))

(defn get-stack
  "Returns a single stack for the given ref."
  [vcs config ref]
  (let [vcs-graph (vcs/read-graph vcs)
        ;; For now, assume ref is a branch name and find the node with that branch
        node (some (fn [[_id node]]
                     (when (some #{ref} (:node/local-branches node))
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

;; TODO precompute local branch-name so it doesn't require the whole VCS
;; client
(defn- is-feature-base-branch?
  "Returns true if the change is a feature base branch."
  [vcs {:keys [feature-base-branches]} change]
  (contains? feature-base-branches (vcs/local-branchname vcs change)))

(defn- truncate-stack-at-feature-base
  "Truncates a stack to stop at the first feature base branch.

  If a feature base branch is found, the stack is cut at that point,
  with the feature base branch as the last element (the base).
  Everything before the feature base (including trunk) is removed.

  Returns the truncated stack, or the original stack if no feature base found."
  [vcs config stack]
  (let [feature-base-branches (:feature-base-branches config)]
    (if (empty? feature-base-branches)
      stack
      (let [feature-base-idx (some (fn [idx]
                                     (when (is-feature-base-branch? vcs config (nth stack idx))
                                       idx))
                                   (range (count stack)))]
        (if feature-base-idx
          ;; Cut the stack from the feature base to the end (including feature base as base)
          (subvec (vec stack) feature-base-idx)
          stack)))))

(comment
  (truncate-stack-at-feature-base
    vcs* config*
    [{:change/description "Main Change"
      :change/local-branches ["main"]}
     {:change/description "Feature 2 merge base"
      :change/local-branches ["feature-2-base"]}
     {:change/description "Feature 2 part 1"
      :change/local-branches ["feature-2-part-1"]}
     {:change/description "Feature 2 part 2"
      :change/local-branches ["feature-2-part-2"]}]))

(defn get-feature-base-stacks
  "Returns stacks for all feature base branches onto trunk.

  Each stack contains [trunk-branch feature-base-branch].
  Only includes feature bases that are direct descendants of trunk
  (i.e., the stack has length 2)."
  [vcs config]
  (let [vcs-graph (vcs/read-graph vcs)
        feature-base-branches (:feature-base-branches config)
        trunk-branch (vcs/trunk-branch vcs)]
    (into []
      (keep (fn [branch-name]
              (let [node (some (fn [[_id node]]
                                 (when (some #{branch-name} (:node/local-branches node))
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
                               (= trunk-branch (vcs/local-branchname vcs (first stack))))
                      stack))))))
      feature-base-branches)))

;;(set! *print-namespace-maps* false)

(defn process-stacks-with-feature-bases
  "Processes stacks to handle feature base branches.

  Returns a map with:
    :regular-stacks - stacks truncated at feature base branches
    :feature-base-stacks - stacks from trunk to each feature base branch"
  [vcs config stacks]
  (let [feature-base-stacks (get-feature-base-stacks vcs config)
        regular-stacks (mapv #(truncate-stack-at-feature-base vcs config %) stacks)]
    {:regular-stacks regular-stacks
     :feature-base-stacks feature-base-stacks}))

(comment
  (require '[prstack.config :as config])
  (def config* (config/read-local))
  (def vcs* (vcs/make config*))
  (tap> (get-current-stacks vcs* config*))
  (tap> (get-stack vcs* config* "test-branch"))
  (get-all-stacks vcs* {:ignored-branches #{}})
  (tap>
   (process-stacks-with-feature-bases vcs* config*
     (get-all-stacks vcs* config*))))
