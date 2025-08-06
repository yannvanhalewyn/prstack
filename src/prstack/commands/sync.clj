(ns prstack.commands.sync
  (:require
    [prstack.commands.create-prs :as create-prs-command]
    [prstack.ui :as ui]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn parse-opts [args]
  {:all? (boolean (some #{"--all"} args))})

(comment
  (parse-opts ["--all"])
  (parse-opts []))

(defn- into-stacks [leaves]
  (doall
    (for [leave leaves]
      (vcs/parse-stack (vcs/get-stack (first (:bookmarks leave)))))))

(def command
  {:name "sync"
   :description "Syncs the current stack with the remote"
   :exec
   (fn sync [args]
     (let [opts (parse-opts args)]
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
         (println "No need to rebase, local master is already up to date with origin."))

       (println (u/colorize :yellow "\nPushing local tracked branches..."))
       (u/shell-out ["jj" "git" "push" "--tracked"] {:echo? true})
       (println "\n")

       (let [stacks
             (if (:all? opts)
               (into-stacks (vcs/get-leaves))
               [(vcs/parse-stack (vcs/get-stack))])]
         (ui/print-stacks stacks)
         (doseq [stack stacks]
           (if (> (count stack) 1)
             (when (u/prompt "Would you like to create missing PRs?")
               ((:exec create-prs-command/command) args))
             (println "No missing PRs to create."))))))})
