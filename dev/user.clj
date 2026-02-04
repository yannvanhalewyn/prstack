(ns user
  (:require
    [clojure.tools.namespace.repl :as repl]
    [portal.api :as p]
    [prstack.config :as config]
    [prstack.github :as github]
    [prstack.stack :as stack]
    [prstack.system :as system]
    [prstack.vcs :as vcs]
    [prstack.vcs.graph :as vcs.graph]))

(require 'hashp.preload)

(def sys-
  (system/new
    (config/read-global)
    (assoc (config/read-local) :vcs :jujutsu)
    {:project-dir "/Users/yannvanhalewyn/spronq/arqiver"}
    #_
      {:project-dir "./tmp/parallel-branches"}))

(defn vcs []
  (:system/vcs sys-))

(defn user-config []
  (:system/user-config sys-))

(def portal-instance (atom nil))

(defn start-portal! []
  (add-tap #'p/submit)
  (reset! portal-instance
    (p/open {:app false
             :launcher nil
             :port 60342})))

(comment
  (repl/refresh)
  (start-portal!))

(comment

  (map #(map :change/selected-branchname %) (stack/get-current-stacks sys-))
  (stack/get-all-stacks sys-)
  (stack/get-current-stacks sys-)
  (github/list-prs (:system/vcs sys-))

  ;; Deeper
  (do
    (vcs/read-relevant-changes (vcs))
    (def vcs-graph- (vcs/read-graph (vcs) (user-config)))
    (def bookmarks-graph- (vcs.graph/bookmarks-subgraph vcs-graph-))
    (def current-id- (vcs/current-change-id (vcs)))
    (vcs.graph/get-node vcs-graph- current-id-)
    (def paths- (vcs.graph/find-all-paths-to-trunk vcs-graph- current-id-)))

  (vcs/read-relevant-changes (:system/vcs sys-))
  (vcs.graph/bookmarked-leaf-nodes bookmarks-graph-)

  (stack/path->stack (first paths-) vcs-graph-)
  (map #(stack/path->stack % vcs-graph-) paths-)
  (map #(vcs.graph/get-node vcs-graph- %) (first paths-)))
