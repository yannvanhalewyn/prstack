(ns prstack.cli.commands.sync
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [prstack.cli.commands.create-prs :as commands.create-prs]
    [prstack.cli.ui :as ui]
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.system :as system]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn parse-opts [args]
  {:all? (boolean (some #{"--all"} args))})

(comment
  (parse-opts ["--all"])
  (parse-opts []))

(defn- trunk-moved? [vcs]
  (let [fork-info (vcs/fork-info vcs)
        trunk-branch (vcs/trunk-branch vcs)
        local-trunk-ref (:fork-info/local-trunk-change-id fork-info)
        remote-trunk-ref (:fork-info/remote-trunk-change-id fork-info)]
    (println (ansi/colorize :yellow "\nChecking if trunk moved"))
    (println (ansi/colorize :cyan "Fork point")
      (:fork-point-info/fork-point-change-id fork-info))
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
       (u/shell-out ["jj" "git" "fetch"]
         {:echo? true})

       (if (trunk-moved? vcs)
         (do
           (println (ansi/colorize :yellow "\nRemote Trunk has changed."))
           (println (format "\nSetting local %s to remote..." (ansi/colorize :blue trunk-branch)))
           (u/shell-out ["jj" "bookmark" "set" trunk-branch
                         "-r" (str trunk-branch "@origin")]
             {:echo? true})
           (when (tty/prompt-confirm
                   {:prompt (format "\nRebase on %s?" (ansi/colorize :blue trunk-branch))})
             (u/shell-out ["jj" "rebase" "-d" trunk-branch]
               {:echo? true})))
         (println (format "Local %s is already up to date with remote. No need to rebase"
                    trunk-branch)))

       (println (ansi/colorize :yellow "\nPushing local tracked branches..."))
       (u/shell-out ["jj" "git" "push" "--tracked"] {:echo? true})
       (println "\n")

       (let [stacks
             (if (:all? opts)
               (stack/get-all-stacks system)
               (stack/get-current-stacks system))
             split-stacks
             (stack/split-feature-base-stacks stacks)]
         (ui/print-stacks split-stacks (assoc opts :include-prs? true))
         (doseq [stack stacks]
           (println "Syncing stack:" (ansi/colorize :blue (first (:change/local-branchnames (last stack)))))
           (if (> (count stack) 1)
             (when (tty/prompt-confirm
                     {:prompt "Would you like to create missing PRs?"})
               (commands.create-prs/create-prs! vcs {:stack stack}))
             (println "No missing PRs to create."))
           (println)))))})
