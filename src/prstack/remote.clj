(ns prstack.remote
  "Top-level namespace for remote repository hosts (GitHub, Bitbucket, ...).

  This namespace defines the RemoteVCS protocol that all remote hosts must
  implement. The concrete implementation is chosen based on the remote URL of
  the repository, see `prstack.system/new-remote`.")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schemas

(def PR
  [:map
   [:pr/title       :string]
   [:pr/number      :int]
   [:pr/url         :string]
   [:pr/author      :string]
   [:pr/head-branch :string]
   [:pr/base-branch :string]
   [:pr/state       :string]
   [:pr/status
    [:enum
     :pr.status/approved
     :pr.status/changes-requested
     :pr.status/review-required
     :pr.status/unknown
     :pr.status/other]]])

(def ^:lsp/allow-unused RemoteError
  [:map
   [:error/type :keyword]
   [:error/message :string]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Protocol Definition

(defprotocol RemoteVCS
  "Protocol defining operations on the remote repository host.

  This protocol abstracts over different hosts (GitHub, Bitbucket, ...) to
  provide a consistent interface for managing the PRs of a stack."

  (list-prs [this opts]
    "Lists the PRs of the repository.

    Options:
      :state - One of :open (default), :merged, :closed or :all

    Returns:
      A tuple of [prs error]. `prs` is a sequence of `PR` maps, `error` is an
      `Error` map when the PRs could not be fetched.")

  (create-pr! [this pr]
    "Creates a PR, possibly prompting the user for title and description.

    Args:
      pr - Map with :pr/head-branch, :pr/base-branch and optionally :pr/title,
           which may be used as a suggestion for the title.

    Returns:
      Truthy when the PR was created, nil when the user cancelled.

    Throws:
      ExceptionInfo if the PR could not be created")

  (merge-pr! [this pr-number]
    "Merges the PR with the given number.

    Throws:
      ExceptionInfo if the PR could not be merged")

  (update-pr-base! [this pr-number new-base]
    "Changes the base (destination) branch of a PR.

    Throws:
      ExceptionInfo if the PR could not be updated"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Host detection

(defn host-type
  "Detects the type of remote host from a remote URL. Returns :bitbucket for
  Bitbucket Cloud remotes, :github otherwise (GitHub Enterprise remotes can have
  any hostname, and `gh` will handle those)."
  [remote-url]
  (if (and remote-url (re-find #"(?i)bitbucket\.org[:/]" remote-url))
    :bitbucket
    :github))

(comment
  (host-type "git@bitbucket.org:workspace/repo.git")
  (host-type "https://yann@bitbucket.org/workspace/repo.git")
  (host-type "git@github.com:yannvanhalewyn/prstack.git")
  (host-type nil))
