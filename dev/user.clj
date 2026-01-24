(ns user
  (:require [portal.api :as p]))

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
