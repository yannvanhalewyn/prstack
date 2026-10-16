(ns prstack.remote.github
  "GitHub implementation of the RemoteVCS protocol, using the GitHub CLI (gh)."
  (:require
    [babashka.json :as json]
    [clojure.set :as set]
    [clojure.string :as str]
    [prstack.remote :as remote]
    [prstack.utils :as u]))

(def ^:private gh-json-keys
  "Maps the `gh pr list --json` keys to PR keys"
  {:title       :pr/title
   :number      :pr/number
   :url         :pr/url
   :author      :pr/author
   :headRefName :pr/head-branch
   :baseRefName :pr/base-branch
   :state       :pr/state})

(def ^:private gh-json-fields
  (conj (keys gh-json-keys) :latestReviews))

(defn- parse-pr [json-output]
  (when json-output
    (assoc (set/rename-keys json-output gh-json-keys)
      :pr/status
      (let [state (:state (first (:latestReviews json-output)))]
        (case state
          "APPROVED" :pr.status/approved
          "CHANGES_REQUESTED" :pr.status/changes-requested
          "REVIEW_REQUIRED" :pr.status/review-required
          (if (str/blank? state)
            :pr.status/unknown
            :pr.status/other))))))

(defn list-prs
  "Lists open PRs by default. Use :state option to filter:
   - :open (default)
   - :merged
   - :closed
   - :all"
  [remote {:keys [state] :or {state :open}}]
  (try
    [(map parse-pr
       (json/read-str
         (u/run-cmd
           ["gh" "pr" "list"
            "--state" (name state)
            "--json" (str/join "," (map name gh-json-fields))]
           {:dir (:remote/project-dir remote)})))]
    (catch Exception e
      (if (= (str/trim (ex-message e)) "no git remotes found")
        [nil {:error/type :github/no-remote-found
              :error/message "No remote found"}]
        [nil {:error/type :github/error-fetching-prs
              :error/message (str/trim (ex-message e))}]))))

(comment
  (list-prs {:remote/project-dir "tmp/parallel-branches"} {}))

(defn create-pr!
  "Create a PR using the GitHub CLI"
  [{:pr/keys [head-branch base-branch]}]
  (u/shell-out-interactive
    ["gh" "pr" "create" "--head" head-branch "--base" base-branch]
    {:echo? true}))

(defn merge-pr!
  "Merge a PR using the GitHub CLI"
  [pr-number]
  ;; This fails using jujutsu after the fact, because there is no branch.
  ;; maybe choose not to delete local branch?
  (u/shell-out
    ["gh" "pr" "merge" (str pr-number)]
    {:echo? true}))

(defn update-pr-base!
  "Updates the base branch of a PR"
  [pr-number new-base]
  (u/shell-out
    ["gh" "pr" "edit" (str pr-number) "--base" new-base]
    {:echo? true}))

(defrecord GitHubRemote []
  remote/RemoteVCS
  (list-prs [this opts]
    (list-prs this opts))

  (create-pr! [_this pr]
    (create-pr! pr))

  (merge-pr! [_this pr-number]
    (merge-pr! pr-number))

  (update-pr-base! [_this pr-number new-base]
    (update-pr-base! pr-number new-base)))

(defn new-remote [project-dir]
  (assoc (->GitHubRemote) :remote/project-dir project-dir))
