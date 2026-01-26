(ns prstack.github
  (:require
    [babashka.json :as json]
    [clojure.string :as str]
    [prstack.tools.schema :as tools.schema]
    [prstack.utils :as u]))

(def PR
  [:map
   [:pr/title       {:gh/json-key :title}       :string]
   [:pr/number      {:gh/json-key :number}      :int]
   [:pr/url         {:gh/json-key :url}         :string]
   [:pr/author      {:gh/json-key :author}      :string]
   [:pr/head-branch {:gh/json-key :headRefName} :string]
   [:pr/base-branch {:gh/json-key :baseRefName} :string]
   [:pr/state       {:gh/json-key :state}       :string]
   [:pr/status
    [:enum
     :pr.status/approved
     :pr.status/changes-requested
     :pr.status/review-required
     :pr.status/unknown
     :pr.status/other]]])

(def GHJsonPR
  (into
    [:map
     [:latestReviews :map]]
    (for [k (keep (comp :gh/json-key tools.schema/properties)
              (tools.schema/entries PR))]
      [k :any])))

(defn- decode-gh-keys [json-output]
  (reduce
    (fn [pr schema-map-entry]
      (if-let [json-key (:gh/json-key (tools.schema/properties schema-map-entry))]
        (assoc (dissoc pr json-key)
          (tools.schema/key schema-map-entry)
          (get json-output json-key))
        pr))
    json-output
    (tools.schema/entries PR)))

(defn- parse-pr [json-output]
  (when json-output
    (assoc (decode-gh-keys json-output)
      :pr/status
      (let [state (:state (first (:latestReviews json-output)))]
        (case (:state (first (:latestReviews json-output)))
          "APPROVED" :pr.status/approved
          "CHANGES_REQUESTED" :pr.status/changes-requested
          "REVIEW_REQUIRED" :pr.status/review-required
          (if (str/blank? state)
            :pr.status/unknown
            :pr.status/other))))))

(defn list-prs-cmd []
  ["gh" "pr" "list" "--json"
   (str/join "," (map name (tools.schema/keys GHJsonPR)))])

(defn parse-prs-cmd-output [output]
  (map parse-pr (json/read-str output)))

(defn list-prs []
  (println
    (decode-gh-keys
     (first
      (json/read-str
       (u/run-cmd
         ["gh" "pr" "list" "--json"
          (str/join "," (map name (tools.schema/keys GHJsonPR)))])))))
  (try
    [(map parse-pr
       (json/read-str
         (u/run-cmd
           ["gh" "pr" "list" "--json"
            (str/join "," (map name (tools.schema/keys GHJsonPR)))])))]
    (catch Exception e
      (if (= (ex-message e) "no git remotes found")
        [nil {:error/type :github/no-remotes
              :error/message (ex-message e)}]
        (throw e)))))

(comment
  (list-prs))

(defn create-pr!
  "Create a PR using the GitHub CLI"
  [head-branch base-branch]
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
