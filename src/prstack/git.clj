(ns prstack.git
  (:require
    [babashka.process :as p]
    [clojure.string :as str]
    [prstack.utils :as u]))

(def bookmark-tree-command
  ["jj" "log" "-r" "trunk()::@ & bookmarks()" "-T" "local_bookmarks ++ \"\n\"" "--no-graph"])

(defn get-bookmark-tree []
  (u/run-cmd bookmark-tree-command))

(defn parse-bookmark-tree [raw-output]
  (->> raw-output
    (str/split-lines)
    (map str/trim)
    (remove empty?)
    (reverse)))

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
