(ns prstack.commands.sync
  (:require
    [bb-tty.tty :as tty]
    [prstack.commands.create-prs :as commands.create-prs]
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.ui :as ui]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn parse-opts [args]
  {:all? (boolean (some #{"--all"} args))})

(comment
  (parse-opts ["--all"])
  (parse-opts []))

(def command
  {:name "sync"
   :description "Syncs the current stack with the remote"
   :exec
   (fn sync [args]
     (let [opts (parse-opts args)
           {:vcs-config/keys [trunk-branch] :as vcs-config} (vcs/config)
           config (config/read-local)]
       (println (u/colorize :yellow "\nFetching branches from remote..."))
       (u/shell-out ["jj" "git" "fetch"]
         {:echo? true})

       (if (vcs/trunk-moved? vcs-config)
         (do
           (println (u/colorize :yellow "\nRemote Trunk has changed."))
           (println (format "\nSetting local %s to remote..." (u/colorize :blue trunk-branch)))
           (u/shell-out ["jj" "bookmark" "set" trunk-branch
                         "-r" (str trunk-branch "@origin")]
             {:echo? true})
           (when (tty/prompt-yes (format "\nRebase on %s?" (u/colorize :blue trunk-branch)))
             (u/shell-out ["jj" "rebase" "-d" trunk-branch]
               {:echo? true})))
         (println (format "Local %s is already up to date with remote. No need to rebase"
                    trunk-branch)))

       (println (u/colorize :yellow "\nPushing local tracked branches..."))
       (u/shell-out ["jj" "git" "push" "--tracked"] {:echo? true})
       (println "\n")

       (let [stacks
             (if (:all? opts)
               (stack/get-all-stacks vcs-config config)
               (stack/get-current-stacks vcs-config))]
         (ui/print-stacks stacks vcs-config (assoc opts :include-prs? true))
         (doseq [stack stacks]
           (println "Syncing stack:" (u/colorize :blue (first (:change/local-branches (last stack)))))
           (if (> (count stack) 1)
             (when (tty/prompt-yes "Would you like to create missing PRs?")
               (commands.create-prs/create-prs {:stack stack}))
             (println "No missing PRs to create."))
           (println)))))})
