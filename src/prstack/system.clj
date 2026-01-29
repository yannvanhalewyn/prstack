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

(defn make
  "Creates a new system with the given configuration.

  Options:
    :project-dir - Directory of the project to operate on. Defaults to current directory."
  ([global-config user-config]
   (make global-config user-config {}))
  ([global-config user-config {:keys [project-dir]}]
   (let [vcs (if (= (:vcs user-config) :git)
               (vcs.git/->GitVCS)
               (vcs.jj/->JujutsuVCS))]
     {:system/global-config global-config
      :system/user-config user-config
      :system/vcs (assoc vcs
                    :vcs/config (vcs/read-vcs-config vcs)
                    :vcs/project-dir project-dir)})))

;; Keep the old name as an alias for backward compatibility
(def new make)
