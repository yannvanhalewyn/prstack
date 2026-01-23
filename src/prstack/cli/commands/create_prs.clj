(ns prstack.cli.commands.create-prs
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [prstack.cli.ui :as ui]
    [prstack.config :as config]
    [prstack.github :as github]
    [prstack.stack :as stack]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn- ensure-remote-branch! [vcs change msg]
  (or (vcs/remote-branchname vcs change)
      (let [branchname (:change/selected-branchname change)]
        (when (tty/prompt-confirm
                {:prompt (str msg (format "Push %s to remote?" (ansi/colorize :blue branchname)))})
          (vcs/push-branch vcs branchname)
          true))))

(defn- prompt-and-create-prs! [head-branch base-branch]
  (when (tty/prompt-confirm
          {:prompt
           (format "Create a PR for %s onto %s?"
             (ansi/colorize :blue head-branch)
             (ansi/colorize :blue base-branch))})
    (github/create-pr! head-branch base-branch)
    (println (ansi/colorize :green "\n✅ Created PR ... \n"))))

(defn create-prs! [vcs {:keys [stack]}]
  (if (seq stack)
    (do
      (println (ansi/colorize :cyan "Let's create the PRs!\n"))
      (doseq [[cur-change next-change] (u/consecutive-pairs stack)]
        (let [head-branch (:change/selected-branchname next-change)
              base-branch (:change/selected-branchname cur-change)
              [pr err] (github/find-pr head-branch base-branch)]
          (if err
            (println (ansi/colorize :red (str "Error: " err)))
            (if pr
              (println
                (format "PR already exists for %s onto %s, skipping. (%s)\n"
                  (ansi/colorize :blue head-branch)
                  (ansi/colorize :blue base-branch)
                  (ansi/colorize :gray (str "#" (:pr/number pr)))))
              (do
                (ansi/colorize :yellow "Checking remote branches")
                (when (ensure-remote-branch! vcs cur-change "Base branch not pushed to remote.")
                  (when (ensure-remote-branch! vcs next-change "Head branch not pushed to remote.")
                    (prompt-and-create-prs! head-branch base-branch)))))))))
    (println (ansi/colorize :cyan "No PRs to create"))))

;; TODO also check if branch is pushed before making PR
(def command
  {:name "create-prs"
   :description "Creates missing PRs in the current stack"
   :exec
   (fn create-prs-cmd [args]
     (let [ref (first args)
           config (config/read-local)
           vcs (vcs/make config)
           stack
           (if ref
             (stack/get-stack vcs config ref)
             (first (stack/get-current-stacks vcs config)))
           processed-stacks
           (stack/process-stacks-with-feature-bases vcs config [stack])]
       (ui/print-stacks processed-stacks {:include-prs? true})
       (create-prs! vcs {:stack stack})))})
