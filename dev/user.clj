(ns user
  (:require
    [clojure.tools.namespace.repl :as repl]
    [portal.api :as p]
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.vcs :as vcs]
    [prstack.vcs.graph :as vcs.graph]))

(require 'hashp.preload)

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

;; Testing out reading vcs graph
(comment
  (def sys- (system/new
              (assoc (config/read-local) :vcs :jujutsu)))

  (stack/get-current-stacks sys-)
  (stack/get-all-stacks sys-)

  ;; Deeper
  (do
    (def vcs-graph- (vcs/read-current-stack-graph sys-))
    (def bookmarks-graph- (vcs.graph/bookmarks-subgraph vcs-graph-))
    (def current-id- (vcs/current-change-id sys-))
    (def paths- (vcs.graph/find-all-paths-to-trunk vcs-graph- current-id-)))

  (vcs/read-current-stack-nodes (:system/vcs sys-))
  (vcs/read-all-nodes (:system/vcs sys-))
  (vcs.graph/bookmarked-leaf-nodes bookmarks-graph-)

  (stack/path->stack (first paths-) vcs-graph-)
  (map #(stack/path->stack % vcs-graph-) paths-)
  (map #(vcs.graph/get-node vcs-graph- %) (first paths-)))
