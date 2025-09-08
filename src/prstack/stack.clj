(ns prstack.stack
  (:require
    [prstack.vcs :as vcs]))

(defn- into-stacks
  [{:keys [ignored-bookmarks]} {:vcs-config/keys [trunk-bookmark] :as vcs-config}
   leaves]
  (into []
    (comp
      (remove (comp #{trunk-bookmark} #(first (:change/local-bookmarks %))))
      (remove (comp ignored-bookmarks #(first (:change/local-bookmarks %))))
      (map #(vcs/get-stack (first (:change/local-bookmarks %)) vcs-config)))
    leaves))

(defn get-all-stacks [vcs-config config]
  (into-stacks config vcs-config (vcs/get-leaves vcs-config)))

(defn get-current-stacks [vcs-config]
  (some-> (vcs/get-stack vcs-config) vector))

(defn get-stack [ref vcs-config]
  (vcs/get-stack ref vcs-config))

(comment
  (get-current-stacks {:vcs-config/trunk-bookmark "main"})
  (get-stack "test-bookmark" {:vcs-config/trunk-bookmark "main"})
  (get-all-stacks {:vcs-config/trunk-bookmark "main"} {:ignored-bookmarks #{}})
  (into-stacks
    {:ignored-bookmarks #{}}
    {:vcs-config/trunk-bookmark "main"}
    [{:change/local-bookmarks ["main"]} ]))
