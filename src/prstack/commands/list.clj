(ns prstack.commands.list
  (:require
    [prstack.git :as git]
    [prstack.ui :as ui]
    [prstack.utils :as u]))

(def command
  {:name "list"
   :description "Lists the current PR stack"
   :exec
   (fn list [args]
     (let [include-prs? (some #{"--include-prs"} args)
           bookmarks (vec (git/parse-bookmark-tree (git/get-bookmark-tree)))
           formatted-bookmarks (map-indexed ui/format-bookmark bookmarks)
           max-width (when (seq bookmarks)
                       (apply max (map count (map-indexed ui/format-bookmark bookmarks))))]
       (doseq [[i [bookmark formatted-bookmark]] (map-indexed vector
                                                   (map vector bookmarks formatted-bookmarks))]
         (let [pr-url (when-let [base-branch (and include-prs? (get bookmarks (dec i)))]
                        (git/find-pr bookmark base-branch))
               padded-bookmark (format (str "%-" max-width "s") formatted-bookmark)]
           (println padded-bookmark (if pr-url (u/colorize :gray (str " (" pr-url ")")) ""))))))})
