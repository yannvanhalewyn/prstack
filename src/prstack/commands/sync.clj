(ns prstack.commands.sync
  (:require
    [prstack.commands.create-prs :as create-prs-command]
    [prstack.ui :as ui]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(def command
  {:name "sync"
   :description "Syncs the current stack with the remote"
   :exec
   (fn sync [args]
     (println (u/colorize :yellow "\nFetching branches from remote..."))
     (u/shell-out ["jj" "git" "fetch"]
       {:echo? true})

     (if (vcs/master-changed?)
       (do
         (println (u/colorize :yellow "\nBumping local master to remote master..."))
         (u/shell-out ["jj" "bookmark" "set" "master" "-r" "master@origin"]
           {:echo? true})
         (when (u/prompt (format "\nRebase on %s?" (u/colorize :blue "master")))
           (u/shell-out ["jj" "rebase" "-d" "master"] {:echo? true})))
       (println (u/colorize :green "\nNo need to rebase, local master is already up to date with origin.")))

     (println (u/colorize :yellow "\nPushing local tracked branches..."))
     (u/shell-out ["jj" "git" "push" "--tracked"] {:echo? true})
     (println "\n")

     (let [bookmark-tree (vcs/parse-bookmark-tree (vcs/get-bookmark-tree))]
       (ui/print-bookmark-tree (vcs/parse-bookmark-tree (vcs/get-bookmark-tree)))

       (if (> (count bookmark-tree) 1)
         (when (u/prompt "Would you like to create missing PRs?")
           ((:exec create-prs-command/command) args))
         (println (u/colorize :green "No missing PRs to create.")))))})
