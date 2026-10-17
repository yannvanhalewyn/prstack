(ns prstack.repo
  "A repo is prstack's handle on an existing git or jj repository. It bundles
  everything needed to operate on that repository: its local config and the VCS
  and remote clients. Global config is not part of a repo, since it's
  shared by all repos."
  (:require
    [clojure.java.io :as io]
    [prstack.config :as config]
    [prstack.remote :as remote]
    [prstack.remote.bitbucket :as remote.bitbucket]
    [prstack.remote.github :as remote.github]
    [prstack.vcs :as vcs]
    [prstack.vcs.git :as vcs.git]
    [prstack.vcs.jujutsu :as vcs.jj]))

(def Repo
  [:map
   ;; Canonical path of the repository, unique across repos
   [:repo/id :string]
   ;; For display
   [:repo/name :string]
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

(defn id
  "Returns the id of the repository at `project-dir`: its canonical path. A nil
  `project-dir` means the current directory."
  [project-dir]
  (.getCanonicalPath (io/file (or project-dir "."))))

(defn default-name
  "Returns the display name for the repository with `repo-id`: its directory
  name."
  [repo-id]
  (.getName (io/file repo-id)))

(defn project-dir
  "Returns the project dir the repo operates on. Nil means the current
  directory."
  [repo]
  (:vcs/project-dir (:repo/vcs repo)))

(defn read-local-config
  "Reads the local config of the repository at `project-dir`. Repositories that
  were never set up with prstack, like the ones in a workspace, don't have one.
  For those the VCS is inferred instead of prompting for setup in every
  repository: jj when it has a `.jj` directory and jj is installed, else git."
  [project-dir]
  (or (config/read-local project-dir)
      {:vcs (if (and (.exists (io/file project-dir ".jj")) (vcs.jj/installed?))
              :jujutsu
              :git)
       :ignored-branches #{}
       :feature-base-branches #{}}))

(defn open
  "Opens the existing repository at `project-dir` as a repo, using the given
  local config, or else its own, see `read-local-config`. A nil `project-dir`
  means the current directory."
  ([project-dir]
   (open project-dir (read-local-config project-dir)))
  ([project-dir local-config]
   (let [vcs (new-vcs project-dir local-config)
         repo-id (id project-dir)]
     {:repo/id repo-id
      :repo/name (default-name repo-id)
      :repo/local-config local-config
      :repo/vcs vcs
      :repo/remote (new-remote vcs)})))
