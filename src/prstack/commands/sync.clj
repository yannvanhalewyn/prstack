(ns prstack.commands.sync
  (:require
    [prstack.commands.create-prs :as create-prs-command]
    [prstack.git :as git]
    [prstack.ui :as ui]
    [prstack.utils :as u]))

(def command
  {:name "sync"
   :description "Syncs the current stack with the remote"
   :exec
   (fn sync [args]
     (println (u/colorize :yellow "\nFetching branches from remote..."))
     (u/shell-out ["jj" "git" "fetch"]
       {:echo? true})

     (println (u/colorize :yellow "\nBumping local master to remote master..."))
     (u/shell-out ["jj" "bookmark" "set" "master" "-r" "master@origin"]
       {:echo? true})

     (when (u/prompt (format "\nRebase on %s?" (u/colorize :blue "master")))
       (u/shell-out ["jj" "rebase" "-d" "master"]
         {:echo? true}))

     (println (u/colorize :yellow "Pushing local tracked branches..."))
     (u/shell-out ["jj" "git" "push" "--tracked"] {:echo? true})

     (let [bookmark-tree (git/parse-bookmark-tree (git/get-bookmark-tree))]
       (ui/print-bookmark-tree (git/parse-bookmark-tree (git/get-bookmark-tree)))

       (if (> (count bookmark-tree) 1)
         (when (u/prompt "\nWould you like to create missing PRs?")
           ((:exec create-prs-command/command) args))
         (println (u/colorize :green "\nNo missing PRs to create.")))))})
