(ns prstack.remote.bitbucket
  "Bitbucket Cloud implementation of the RemoteVCS protocol.

  There is no official Bitbucket equivalent of the GitHub CLI, so this talks to
  the Bitbucket Cloud REST API (https://api.bitbucket.org/2.0) directly.

  Authentication is read from environment variables, either:
    - BITBUCKET_EMAIL + BITBUCKET_API_TOKEN: your Atlassian account email and
      an API token (https://id.atlassian.com/manage-profile/security/api-tokens)
      with the read:pullrequest:bitbucket and write:pullrequest:bitbucket scopes.
    - BITBUCKET_ACCESS_TOKEN: a repository, project or workspace access token
      with the pullrequest:write scope."
  (:require
    [babashka.http-client :as http]
    [babashka.json :as json]
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [clojure.string :as str]
    [prstack.remote :as remote]))

(def ^:private api-url "https://api.bitbucket.org/2.0")

;; Bitbucket's maximum page size for pull requests
(def ^:private page-size 50)

;; Bitbucket returns PRs sorted by most recently updated. Fetching all pages
;; for e.g. merged PRs could take a very long time in older repositories, so
;; like `gh pr list` we only look at the most recent ones.
(def ^:private max-pages 4)

(def ^:private pr-fields
  "Only request the fields we need, including participants which are left out
  of the list endpoint by default."
  (str/join ","
    ["next"
     "values.id"
     "values.title"
     "values.state"
     "values.author.display_name"
     "values.links.html.href"
     "values.source.branch.name"
     "values.destination.branch.name"
     "values.participants.role"
     "values.participants.approved"
     "values.participants.state"]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Remote URL

(defn parse-remote-url
  "Parses the workspace and repo slug out of a Bitbucket remote URL, e.g.
    git@bitbucket.org:workspace/repo.git
    https://user@bitbucket.org/workspace/repo.git"
  [remote-url]
  (when-let [[_ workspace repo-slug]
             (re-find #"(?i)bitbucket\.org[:/]([^/]+)/([^/]+?)(?:\.git)?/?$"
               (str/trim (str remote-url)))]
    {:bitbucket/workspace workspace
     :bitbucket/repo-slug repo-slug}))

(comment
  (parse-remote-url "git@bitbucket.org:workspace/my-repo.git")
  (parse-remote-url "https://yann@bitbucket.org/workspace/my-repo.git")
  (parse-remote-url "ssh://git@bitbucket.org/workspace/my-repo"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP

(defn- getenv [k]
  (some-> (System/getenv k) str/trim))

(defn- auth-headers []
  (let [email (getenv "BITBUCKET_EMAIL")
        api-token (getenv "BITBUCKET_API_TOKEN")
        access-token (getenv "BITBUCKET_ACCESS_TOKEN")]
    (cond
      (and (not (str/blank? email)) (not (str/blank? api-token)))
      {"Authorization"
       (str "Basic " (.encodeToString (java.util.Base64/getEncoder)
                       (.getBytes (str email ":" api-token) "UTF-8")))}

      (not (str/blank? access-token))
      {"Authorization" (str "Bearer " access-token)}

      :else
      (throw
        (ex-info
          (str "Bitbucket credentials not found. Set BITBUCKET_EMAIL and "
               "BITBUCKET_API_TOKEN, or BITBUCKET_ACCESS_TOKEN.")
          {:error/type :bitbucket/missing-credentials})))))

(defn- error-hint [remote status]
  (case (int status)
    401 (str "Authentication failed. Check that BITBUCKET_EMAIL is your "
             "Atlassian account email and BITBUCKET_API_TOKEN is an API token "
             "created with scopes for the Bitbucket app.")
    403 (str "The token lacks the required scopes. It needs "
             "read:pullrequest:bitbucket and write:pullrequest:bitbucket.")
    404 (format "Repository %s/%s not found, or you don't have access to it."
          (:bitbucket/workspace remote) (:bitbucket/repo-slug remote))
    nil))

(defn- error-detail
  "Bitbucket includes the required and granted scopes in the error detail when
  a token is missing scopes."
  [detail]
  (cond
    (and (map? detail) (or (:required detail) (:granted detail)))
    (str " Required scopes: " (str/join ", " (:required detail))
         ". Granted scopes: " (str/join ", " (:granted detail)) ".")

    (some? detail)
    (str " " (if (string? detail) detail (json/write-str detail)))))

(defn- error-message [remote response]
  (let [body (try (json/read-str (:body response)) (catch Exception _ nil))]
    (str "Bitbucket API error (" (:status response) "): "
         (or (when-let [message (get-in body [:error :message])]
               (str message (error-detail (get-in body [:error :detail]))))
             (error-hint remote (:status response))
             (not-empty (str/trim (str (:body response))))
             "Unknown error"))))

(defn- request!
  "Performs a request to the Bitbucket API. Either pass a `:path` relative to
  the repository, or a full `:url` (like the `next` link of a page). Returns
  the parsed JSON body, throws on non-2xx responses."
  [remote {:keys [method path url query-params body]}]
  (let [response
        (http/request
          (cond-> {:method (or method :get)
                   :uri (or url
                            (format "%s/repositories/%s/%s%s" api-url
                              (:bitbucket/workspace remote)
                              (:bitbucket/repo-slug remote)
                              path))
                   :headers (cond-> (merge {"Accept" "application/json"}
                                      (auth-headers))
                              body (assoc "Content-Type" "application/json"))
                   :throw false}
            query-params (assoc :query-params query-params)
            body (assoc :body (json/write-str body))))]
    (if (<= 200 (:status response) 299)
      (when-not (str/blank? (:body response))
        (json/read-str (:body response)))
      (throw (ex-info (error-message remote response)
               {:error/type :bitbucket/api-error
                :http/status (:status response)})))))

(defn- request-pages!
  "Requests a paginated resource, following the `next` links up to `max-pages`.
  Returns all values."
  [remote req]
  (loop [page (request! remote req)
         values []
         n 1]
    (let [values (into values (:values page))]
      (if (and (:next page) (< n max-pages))
        (recur (request! remote {:url (:next page)}) values (inc n))
        values))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pull Requests

(defn- review-status [participants]
  (let [states (set (keep :state participants))]
    (cond
      (contains? states "changes_requested") :pr.status/changes-requested
      (or (contains? states "approved")
          (some :approved participants))     :pr.status/approved
      (some #(= "REVIEWER" (:role %)) participants) :pr.status/review-required
      :else                                  :pr.status/unknown)))

(defn- parse-pr [pr]
  {:pr/number (:id pr)
   :pr/title (:title pr)
   :pr/url (get-in pr [:links :html :href])
   :pr/author (get-in pr [:author :display_name])
   :pr/head-branch (get-in pr [:source :branch :name])
   :pr/base-branch (get-in pr [:destination :branch :name])
   :pr/state (:state pr)
   :pr/status (review-status (:participants pr))})

(defn- bitbucket-states [state]
  (case state
    :open ["OPEN"]
    :merged ["MERGED"]
    :closed ["DECLINED" "SUPERSEDED"]
    :all ["OPEN" "MERGED" "DECLINED" "SUPERSEDED"]))

(defn list-prs
  "Lists open PRs by default. Use :state option to filter:
   - :open (default)
   - :merged
   - :closed
   - :all"
  [remote {:keys [state] :or {state :open}}]
  (try
    [(map parse-pr
       (request-pages! remote
         {:path "/pullrequests"
          :query-params {"state" (bitbucket-states state)
                         "sort" "-updated_on"
                         "pagelen" (str page-size)
                         "fields" pr-fields}}))]
    (catch Exception e
      [nil {:error/type (or (:error/type (ex-data e))
                            :bitbucket/error-fetching-prs)
            :error/message (ex-message e)}])))

(comment
  (def remote- (merge (->BitbucketRemote)
                 (parse-remote-url "git@bitbucket.org:atlassian/aui.git")))
  (list-prs remote- {:state :merged}))

(defn create-pr!
  "Creates a PR, prompting the user for a title and description."
  [remote {:pr/keys [head-branch base-branch title]}]
  (when-let [title (tty/prompt-input
                     {:prompt (format "PR title for %s onto %s"
                                (ansi/colorize :blue head-branch)
                                (ansi/colorize :blue base-branch))
                      :value (or title head-branch)})]
    (let [description (tty/prompt-write
                        {:prompt "PR description (ctrl+d to submit, esc to leave empty)"})
          pr (request! remote
               {:method :post
                :path "/pullrequests"
                :body {:title title
                       :description (or description "")
                       :source {:branch {:name head-branch}}
                       :destination {:branch {:name base-branch}}
                       ;; Deleting the source branch would break PRs that are
                       ;; stacked on top of this one.
                       :close_source_branch false}})]
      (println (ansi/colorize :gray (get-in pr [:links :html :href])))
      pr)))

(defn merge-pr!
  "Merges a PR using the default merge strategy of the repository."
  [remote pr-number]
  (println (ansi/colorize :gray (str "Merging PR #" pr-number " on Bitbucket...")))
  (let [response (request! remote
                   {:method :post
                    :path (str "/pullrequests/" pr-number "/merge")})]
    ;; Long running merges return a 202 with a task status instead of the PR
    (if (= "MERGED" (:state response))
      (println (ansi/colorize :green "✔") "Merged PR" (str "#" pr-number))
      (println (ansi/colorize :yellow "●") "Merge of PR" (str "#" pr-number)
        "is in progress"))
    response))

(defn update-pr-base!
  "Updates the destination branch of a PR"
  [remote pr-number new-base]
  (let [pr (request! remote {:path (str "/pullrequests/" pr-number)
                             :query-params {"fields" "title"}})]
    ;; Bitbucket requires the title to be present when updating a PR
    (request! remote
      {:method :put
       :path (str "/pullrequests/" pr-number)
       :body {:title (:title pr)
              :destination {:branch {:name new-base}}}})))

(defrecord BitbucketRemote []
  remote/RemoteVCS
  (list-prs [this opts]
    (list-prs this opts))

  (create-pr! [this pr]
    (create-pr! this pr))

  (merge-pr! [this pr-number]
    (merge-pr! this pr-number))

  (update-pr-base! [this pr-number new-base]
    (update-pr-base! this pr-number new-base)))

(defn new-remote [remote-url]
  (if-let [repo (parse-remote-url remote-url)]
    (merge (->BitbucketRemote) repo)
    (throw (ex-info (str "Could not parse Bitbucket remote URL: " remote-url)
             {:remote-url remote-url}))))
