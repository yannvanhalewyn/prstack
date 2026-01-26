(ns prstack.pr
  (:require
    [prstack.utils :as u]))

(defn find-pr
  [prs head-branch base-branch]
  (u/find-first
    #(= (:pr/head-branch %) head-branch
        (:pr/base-branch %) base-branch)
    prs))

