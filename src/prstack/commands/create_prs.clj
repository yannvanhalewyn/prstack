(ns prstack.commands.create-prs
  (:require
    [prstack.git :as git]
    [prstack.ui :as ui]
    [prstack.utils :as u]))

(def command
  {:name "create"
   :description "Creates missing PRs in the current stack"
   :exec
   (fn create-prs [_args]
     (let [bookmarks (git/parse-bookmark-tree (git/get-bookmark-tree))]
       (ui/print-bookmark-tree bookmarks)

       (println (u/colorize :cyan "Let's create the PRs!\n"))
       (doseq [[base-branch head-branch] (u/consecutive-pairs bookmarks)]
         (let [pr-url (git/find-pr head-branch base-branch)]
           (if pr-url
             (println
               (format "PR already exists for %s onto %s, skipping. (%s)"
                 (u/colorize :blue head-branch)
                 (u/colorize :blue base-branch)
                 (u/colorize :gray pr-url)))
             (when (u/prompt
                     (format "Create a PR for %s onto %s?"
                       (u/colorize :blue head-branch)
                       (u/colorize :blue base-branch)))
               (git/create-pr! head-branch base-branch)
               (println (u/colorize :green "\n✅ Created PR ... \n"))))))))})
