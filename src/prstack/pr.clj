(ns prstack.pr
  (:require
    [prstack.utils :as u]))

(defn find-pr
  "Find a PR by head branch.
  
  Returns the first PR found for the given head branch, regardless of base branch."
  [prs head-branch]
  (u/find-first
    #(= (:pr/head-branch %) head-branch)
    prs))
