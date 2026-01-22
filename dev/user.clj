(ns user
  (:require
    [portal.api :as p]
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.vcs :as vcs]))

(require 'hashp.preload)

(def portal-instance (atom nil))

(defn start-portal! []
  (add-tap #'p/submit)
  (reset! portal-instance
    (p/open {:app false
             :launcher nil
             :port 60342})))

(comment
  (start-portal!)

;; Testing out reading vcs graph
(comment
  (def config- (config/read-local))
  (def vcs- (vcs/make config-))
  (stack/get-current-stacks vcs- config-)
  (stack/get-all-stacks vcs- config-))
  )
