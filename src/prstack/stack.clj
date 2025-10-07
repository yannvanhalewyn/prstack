(ns prstack.stack
  (:require
    [prstack.vcs :as vcs]))

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

(defn- into-stacks
  [{:keys [ignored-branches]} {:vcs-config/keys [trunk-branch] :as vcs-config}
   leaves]
  (into []
    (comp
      (remove (comp #{trunk-branch} vcs/local-branchname))
      (remove (comp ignored-branches vcs/local-branchname))
      (map #(vcs/get-stack (vcs/local-branchname %) vcs-config)))
    leaves))

(defn get-all-stacks [vcs-config config]
  (into-stacks config vcs-config (vcs/get-leaves vcs-config)))

(defn get-current-stacks [vcs-config config]
  (if-let [megamerge (vcs/find-megamerge "@")]
    (into-stacks config vcs-config (vcs/parents megamerge))
    (some-> (vcs/get-stack vcs-config) vector)))

(defn get-stack [ref vcs-config]
  (vcs/get-stack ref vcs-config))

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
    {:ignored-branches #{}})
  (into-stacks {:ignored-branches #{}} (vcs/config)
    (vcs/parents (vcs/find-megamerge "@")))
  (into-stacks
    {:ignored-branches #{}}
    {:vcs-config/trunk-branch "main"}
    [{:change/change-id "ptkkppxnltpv",
      :change/local-branches ["feature-2" "feature-3"],
      :change/remote-branches ["feature-2@git" "feature-3@git"]}
     {:change/change-id "vwvuswlzsnlx",
      :change/local-branches ["hotfix-edit" "second-bookmark"],
      :change/remote-branches
      ["hotfix-edit@git"
       "hotfix-edit@origin"
       "second-bookmark@git"
       "second-bookmark@origin"]}]
    #_
    [{:change/local-branches ["main"]} ]))
