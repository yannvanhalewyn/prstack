(ns prstack.commands.sync
  (:require
    [prstack.commands.create-prs :as create-prs-command]
    [prstack.git :as git]
    [prstack.ui :as ui]
    [prstack.utils :as u]))

(defn run []
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

  (ui/print-bookmark-tree (git/parse-bookmark-tree (git/get-bookmark-tree)))

  (when (u/prompt "\nWould you like to create missing PRs?")
    (create-prs-command/run)))
