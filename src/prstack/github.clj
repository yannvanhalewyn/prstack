(ns prstack.github
  (:require
    [babashka.json :as json]
    [clojure.string :as str]
    [prstack.utils :as u]))

(def ^:lsp/allow-unused PR
  [:map
   [:pr/title :string]
   [:pr/number :int]
   [:pr/url :string]
   [:pr/status
    [:enum
     :pr.status/approved
     :pr.status/changes-requested
     :pr.status/review-required
     :pr.status/unknown
     :pr.status/other]]])

(defn- parse-pr [json-output]
  (when json-output
    {:pr/title (:title json-output)
     :pr/number (:number json-output)
     :pr/url (:url json-output)
     :pr/status
     (let [state (:state (first (:latestReviews json-output)))]
       (case (:state (first (:latestReviews json-output)))
         "APPROVED" :pr.status/approved
         "CHANGES_REQUESTED" :pr.status/changes-requested
         "REVIEW_REQUIRED" :pr.status/review-required
         (if (str/blank? state)
           :pr.status/unknown
           :pr.status/other)))}))

(defn find-pr
  "Find a PR using the GitHub CLI"
  [head-branch base-branch]
  (let [[result err]
        (try
          [(u/run-cmd ["gh" "pr" "list"
                       "--head" head-branch
                       "--base" base-branch
                       "--limit" "1"
                       "--json" "title,number,url,latestReviews" "--jq" ".[0]"])]
          (catch Exception e
            (if (= (ex-message e) "no git remotes found")
              [nil {:error/type :github/no-remotes
                    :error/message "No remotes found."}]
              (throw e))))]
    (if result
      [(parse-pr (json/read-str (not-empty result)))]
      [nil err])))

(defn create-pr!
  "Create a PR using the GitHub CLI"
  [head-branch base-branch]
  (comment ;; This works as expected
    (u/shell-out
      ["echo" "pr" "create" "--head" head-branch "--base" base-branch]
      {:echo? true})
    (println (.read System/in))
    (Thread/sleep 5000))
  ;; This doesn't
  (u/shell-out-interactive
    ["gh" "pr" "create" "--head" head-branch "--base" base-branch]
    {:echo? true}))

(defn merge-pr!
  "Merge a PR using the GitHub CLI"
  [pr-number]
  ;; This fails using jujutsu after the fact, because there is no branch.
  ;; maybe choose not to delete local branch?
  (u/shell-out
    ["gh" "pr" "merge" pr-number]
    {:echo? true}))
