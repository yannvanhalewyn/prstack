(ns prstack.vcs
  (:require
    [babashka.process :as p]
    [clojure.string :as str]
    [prstack.utils :as u]))

(def bookmark-tree-command
  ["jj" "log" "-r" "fork_point(trunk() | @)::@ & bookmarks()" "-T" "local_bookmarks ++ \"\n\"" "--no-graph"])

(defn- ensure-trunk-bookmark
  "Sometimes the trunk bookmark has moved but the stack was not rebased."
  [bookmarks]
  (if (= (first bookmarks) "master")
    bookmarks
    (cons "master" bookmarks)))

(defn get-bookmark-tree []
  (u/run-cmd bookmark-tree-command))

(defn parse-bookmark-tree [raw-output]
  (->> raw-output
    (str/split-lines)
    (map str/trim)
    (remove empty?)
    (reverse)
    (ensure-trunk-bookmark)))

(defn master-changed? []
  (let [local-master-id (u/run-cmd ["jj" "log" "-r" "master" "-T" "commit_id" "--no-graph"])
        origin-master-id (u/run-cmd ["jj" "log" "-r" "master@origin" "-T" "commit_id" "--no-graph"])]
    (not= local-master-id origin-master-id)))

(defn create-pr! [head-branch base-branch]
  (->
    (p/shell {:inherit true}
      "gh" "pr" "create" "--head" head-branch "--base" base-branch)
    p/check
    :out
    slurp))

(defn find-pr
  [head-branch base-branch]
  (not-empty
    (u/run-cmd ["gh" "pr" "list"
                "--head" head-branch
                "--base" base-branch
                "--limit" "1"
                "--json" "url" "--jq" ".[0].url"])))
