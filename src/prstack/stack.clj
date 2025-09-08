(ns prstack.stack
  (:require
    [prstack.vcs :as vcs]))

(defn- into-stacks
  [{:keys [ignored-bookmarks]} {:vcs-config/keys [trunk-bookmark] :as vcs-config} leaves]
  (into []
    (comp
      ;;(remove (comp #{trunk-bookmark} #(first (:bookmarks %))))
      (remove (comp ignored-bookmarks #(first (:bookmarks %))))
      (map #(vcs/get-stack (first (:bookmarks %)) vcs-config)))
    leaves))

(defn get-all-stacks [vcs-config config]
  (into-stacks config vcs-config (vcs/get-leaves vcs-config)))

(defn get-current-stacks [vcs-config]
  (or (some-> (vcs/get-stack vcs-config) vector) []))

(comment
  (get-current-stacks {:vcs-config/trunk-bookmark "main"})
  (into-stacks
    {:ignored-bookmarks #{}}
    {:vcs-config/trunk-bookmark "main"}
    [{:bookmarks ["main"]} ]))
