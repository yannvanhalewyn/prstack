(ns user
  (:require
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
  (start-portal!))

(comment
  (vcs/read-current-stack-nodes vcs-))

;; Testing out reading vcs graph
(comment
  (do
    (def config- (assoc (config/read-local) :vcs :jujutsu))
    (def vcs- (vcs/make config-)))

  (stack/get-current-stacks vcs- config-)
  (stack/get-all-stacks vcs- config-)

  ;; Deeper
  (do
    (def vcs-graph- (vcs/read-current-stack-graph vcs- config-))
    (def bookmarks-graph- (vcs.graph/bookmarks-subgraph vcs-graph-))
    (def current-id- (vcs/current-change-id vcs-))
    (def paths- (vcs.graph/find-all-paths-to-trunk vcs-graph- current-id-)))
  (stack/path->stack vcs-graph- (first paths-))
  (map #(stack/path->stack vcs-graph- % config-) paths-)
  (map #(vcs.graph/get-node vcs-graph- %) (first paths-)))
