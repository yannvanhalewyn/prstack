(ns prstack.system
  (:require
    [prstack.config :as config]
    [prstack.remote :as remote]
    [prstack.remote.bitbucket :as remote.bitbucket]
    [prstack.remote.github :as remote.github]
    [prstack.vcs :as vcs]
    [prstack.vcs.git :as vcs.git]
    [prstack.vcs.jujutsu :as vcs.jj]))

;; `System` is mapped to `java.lang.System`
(def SystemSchema
  [:map
   [:system/global-config config/GlobalConfig]
   [:system/user-config config/UserConfig]
   [:system/vcs vcs/VCS]
   [:system/remote remote/RemoteVCS]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API

(defn new-vcs [user-config global-opts]
  (let [vcs (if (= (:vcs user-config) :git)
              (vcs.git/->GitVCS)
              (vcs.jj/->JujutsuVCS))]
    (as-> (assoc vcs :vcs/project-dir (:project-dir global-opts)) $
      (assoc $ :vcs/config (vcs/read-vcs-config $)))))

(defn new-remote
  "Creates the RemoteVCS implementation matching the origin remote URL."
  [vcs]
  (let [remote-url (vcs/remote-url vcs)]
    (case (remote/host-type remote-url)
      :bitbucket (remote.bitbucket/new-remote remote-url)
      :github (remote.github/new-remote (:vcs/project-dir vcs)))))

(defn project-dir
  "Returns the project dir the system operates on. Nil means the current
  directory."
  [system]
  (:vcs/project-dir (:system/vcs system)))

(defn new
  "Creates a new system with the given configuration.

  Options:
    :project-dir - Directory of the project to operate on. Defaults to current directory."
  [global-config user-config global-opts]
  (let [vcs (new-vcs user-config global-opts)]
    {:system/global-config global-config
     :system/user-config user-config
     :system/vcs vcs
     :system/remote (new-remote vcs)}))
