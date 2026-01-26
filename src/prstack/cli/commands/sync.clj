(ns prstack.cli.commands.sync
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [prstack.cli.commands.create-prs :as commands.create-prs]
    [prstack.cli.ui :as cli.ui]
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.system :as system]
    [prstack.ui :as ui]
    [prstack.vcs :as vcs]))

(defn parse-opts [args]
  {:all? (boolean (some #{"--all"} args))})

(comment
  (parse-opts ["--all"])
  (parse-opts []))

(defn- trunk-moved? [vcs]
  (let [fork-info (vcs/fork-info vcs)
        trunk-branch (vcs/trunk-branch vcs)
        local-trunk-ref (:forkpoint-info/local-trunk-commit-sha fork-info)
        remote-trunk-ref (:forkpoint-info/remote-trunk-commit-sha fork-info)]
    (println (ansi/colorize :yellow "\nChecking if trunk moved"))
    (println (ansi/colorize :cyan "Fork point")
      (:forkpoint-info/fork-point-change-id fork-info))
    (println (ansi/colorize :cyan (str "local " trunk-branch)) local-trunk-ref)
    (println (ansi/colorize :cyan (str "remote " trunk-branch)) remote-trunk-ref)
    (not= local-trunk-ref remote-trunk-ref)))

(def command
  {:name "sync"
   :flags [["--all" "-a" "Looks all your stacks, not just the current one"]]
   :description "Syncs the current stack with the remote"
   :exec
   (fn sync [args]
     (let [opts (parse-opts args)
           system (system/new (config/read-local))
           vcs (:system/vcs system)
           {:vcs-config/keys [trunk-branch]} (vcs/vcs-config vcs) ]
       (println (ansi/colorize :yellow "\nFetching branches from remote..."))
       (vcs/fetch! vcs)

       (if (trunk-moved? vcs)
         (do
           (println (ansi/colorize :yellow "\nRemote Trunk has changed."))
           (println (format "\nSetting local %s to remote..." (ansi/colorize :blue trunk-branch)))
           (vcs/set-bookmark-to-remote! vcs trunk-branch)
           (when (tty/prompt-confirm
                   {:prompt (format "\nRebase on %s?" (ansi/colorize :blue trunk-branch))})
             (vcs/rebase-on-trunk! vcs)))
         (println (format "Local %s is already up to date with remote. No need to rebase"
                    trunk-branch)))

       (println (ansi/colorize :yellow "\nPushing local tracked branches..."))
       (vcs/push-tracked! vcs)
       (println "\n")

       (let [stacks
             (if (:all? opts)
               (stack/get-all-stacks system)
               (stack/get-current-stacks system))
             split-stacks
             (stack/split-feature-base-stacks stacks)
             prs (ui/fetch-prs-with-spinner)]
         (cli.ui/print-stacks split-stacks prs)
         (doseq [stack stacks]
           (println "Syncing stack:" (ansi/colorize :blue (first (:change/local-branchnames (last stack)))))
           (if (> (count stack) 1)
             (when (tty/prompt-confirm
                     {:prompt "Would you like to create missing PRs?"})
               (commands.create-prs/create-prs! vcs {:prs prs :stack stack}))
             (println "No missing PRs to create."))
           (println)))))})
