(ns prstack.system
  (:require
    [prstack.config :as config]
    [prstack.vcs :as vcs]
    [prstack.vcs.git :as vcs.git]
    [prstack.vcs.jujutsu :as vcs.jj]))

;; `System` is mapped to `java.lang.System`
(def SystemSchema
  [:map
   [:system/global-config config/GlobalConfig]
   [:system/user-config config/UserConfig]
   [:system/vcs vcs/VCS]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API

(defn new [global-config user-config]
  (let [vcs (if (= (:vcs user-config) :git)
              (vcs.git/->GitVCS)
              (vcs.jj/->JujutsuVCS))]
    {:system/global-config global-config
     :system/user-config user-config
     :system/vcs (assoc vcs :vcs/config (vcs/read-vcs-config vcs))}))
