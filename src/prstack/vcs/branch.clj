(ns prstack.vcs.branch
  (:require
    [prstack.vcs.graph :as vcs.graph]))

(def Branch
  [:map
   [:branch/branchname :string]
   [:branch/local-change-id :string]
   [:branch/remote-change-id :string]
   [:branch/status [:enum
                    :branch.status/up-to-date
                    :branch.status/behind
                    :branch.status/ahead
                    :branch.status/diverged]]])

(defn find-change-for-local-branchname [vcs-graph local-branchname]
  (first
    (vcs.graph/find-nodes vcs-graph
      #(some #{local-branchname} (:change/local-branchnames %)))))

(defn find-change-for-remote-branchname [vcs-graph remote-branchname]
  (first
    (vcs.graph/find-nodes vcs-graph
      #(some #{remote-branchname} (:change/remote-branchnames %)))))

(defn selected-branches-info
  "Returns a list of selected branches in the VCS graph. Each element will have
  a reference to the local change ID, remote change ID and a status"
  [vcs-graph]
  (for [branchname (vcs.graph/all-selected-branchnames vcs-graph)
        :let [local (find-change-for-local-branchname vcs-graph branchname)
              remote (find-change-for-remote-branchname vcs-graph branchname)
              local-id (:change/change-id local)
              remote-id (:change/change-id remote)]]
    {:branch/branchname branchname
     :branch/local-change-id local-id
     :branch/remote-change-id remote-id
     :branch/status
     (cond
       (nil? remote-id) :branch.status/no-remote
       (= local-id remote-id) :branch.status/up-to-date
       (vcs.graph/is-ancestor? vcs-graph local-id remote-id) :branch.status/behind
       (vcs.graph/is-ancestor? vcs-graph remote-id local-id) :branch.status/ahead
       :else :branch.status/diverged)}))

(defn up-to-date? [branch]
  (= (:branch/status branch) :branch.status/up-to-date))

(defn behind? [branch]
  (= (:branch/status branch) :branch.status/behind))

(defn ahead? [branch]
  (= (:branch/status branch) :branch.status/ahead))

(defn diverged? [branch]
  (= (:branch/status branch) :branch.status/diverged))
