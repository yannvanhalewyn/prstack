(ns prstack.commands.sync
  (:require
    [prstack.commands.create-prs :as create-prs-command]
    [prstack.config :as config]
    [prstack.ui :as ui]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn parse-opts [args]
  {:all? (boolean (some #{"--all"} args))})

(comment
  (parse-opts ["--all"])
  (parse-opts []))

(defn- into-stacks [{:keys [ignored-bookmarks]} vcs-config leaves]
  (into []
    (comp
      (remove (comp ignored-bookmarks #(first (:bookmarks %))))
      (map #(vcs/get-stack (first (:bookmarks %)) vcs-config)))
    leaves))

(def command
  {:name "sync"
   :description "Syncs the current stack with the remote"
   :exec
   (fn sync [args]
     (let [opts (parse-opts args)
           {:vcs-config/keys [trunk-bookmark] :as vcs-config} (vcs/config)
           config (config/read-local)]
       (println (u/colorize :yellow "\nFetching branches from remote..."))
       (u/shell-out ["jj" "git" "fetch"]
         {:echo? true})

       (if (vcs/trunk-moved? vcs-config)
         (do
           (println (u/colorize :yellow "\nRemote Trunk has changed."))
           (println (format "\nSetting local %s to remote..." (u/colorize :blue trunk-bookmark)))
           (u/shell-out ["jj" "bookmark" "set" trunk-bookmark
                         "-r" (str trunk-bookmark "@origin")]
             {:echo? true})
           (when (u/prompt (format "\nRebase on %s?" (u/colorize :blue trunk-bookmark)))
             (u/shell-out ["jj" "rebase" "-d" trunk-bookmark]
               {:echo? true})))
         (println (format "Local %s is already up to date with remote. No need to rebase"
                    trunk-bookmark)))

       (println (u/colorize :yellow "\nPushing local tracked branches..."))
       (u/shell-out ["jj" "git" "push" "--tracked"] {:echo? true})
       (println "\n")

       (let [stacks
             (if (:all? opts)
               (into-stacks config vcs-config (vcs/get-leaves vcs-config))
               (into [] (remove nil?) [(vcs/get-stack vcs-config)]))]
         (ui/print-stacks stacks)
         (doseq [stack stacks]
           (println "Syncing stack:" (u/colorize :blue (last stack)))
           (if (> (count stack) 1)
             (when (u/prompt "Would you like to create missing PRs?")
               ((:exec create-prs-command/command) args))
             (println "No missing PRs to create."))
           (println)))))})
