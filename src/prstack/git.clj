(ns prstack.git
  (:require
   [prstack.utils :as utils]
   [clojure.string :as str]))

(def bookmark-tree-command
  ["jj" "log" "-r" "trunk()::@ & bookmarks()" "-T" "local_bookmarks ++ \"\n\"" "--no-graph"])

(defn get-bookmark-tree []
  (utils/run-cmd bookmark-tree-command))

(defn parse-bookmark-tree [raw-output]
  (->> raw-output
    (str/split-lines)
    (map str/trim)
    (remove empty?)
    (reverse)))

(defn create-pr-command [head-branch base-branch]
  (format "gh pr create --head %s --base %s"
    head-branch base-branch))

(defn create-pr [head-branch base-branch]
  (println ;; utils/run-cmd
    (create-pr-command head-branch base-branch)))