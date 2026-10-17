(ns prstack.repo
  "A repo is prstack's handle on an existing git or jj repository. It bundles
  everything needed to operate on that repository: its local config and the VCS
  and remote clients. Global config is not part of a repo, since it's
  shared by all repos."
  (:require
    [prstack.config :as config]
    [prstack.remote :as remote]
    [prstack.remote.bitbucket :as remote.bitbucket]
    [prstack.remote.github :as remote.github]
    [prstack.vcs :as vcs]
    [prstack.vcs.git :as vcs.git]
    [prstack.vcs.jujutsu :as vcs.jj]))

(def Repo
  [:map
   [:repo/local-config config/LocalConfig]
   [:repo/vcs vcs/VCS]
   [:repo/remote remote/RemoteVCS]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API

(defn new-vcs
  "Creates the VCS client for the project at `project-dir`. A nil
  `project-dir` means the current directory."
  [project-dir local-config]
  (let [vcs (if (= (:vcs local-config) :git)
              (vcs.git/->GitVCS)
              (vcs.jj/->JujutsuVCS))]
    (as-> (assoc vcs :vcs/project-dir project-dir) $
      (assoc $ :vcs/config (vcs/read-vcs-config $)))))

(defn new-remote
  "Creates the RemoteVCS implementation matching the origin remote URL."
  [vcs]
  (let [remote-url (vcs/remote-url vcs)]
    (case (remote/host-type remote-url)
      :bitbucket (remote.bitbucket/new-remote remote-url)
      :github (remote.github/new-remote (:vcs/project-dir vcs)))))

(defn project-dir
  "Returns the project dir the repo operates on. Nil means the current
  directory."
  [repo]
  (:vcs/project-dir (:repo/vcs repo)))

(defn open
  "Opens the existing repository at `project-dir` as a repo, using the given
  local config. A nil `project-dir` means the current directory."
  [project-dir local-config]
  (let [vcs (new-vcs project-dir local-config)]
    {:repo/local-config local-config
     :repo/vcs vcs
     :repo/remote (new-remote vcs)}))
