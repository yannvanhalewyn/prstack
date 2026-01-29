(ns prstack.cli.commands.create-prs
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [prstack.cli.ui :as cli.ui]
    [prstack.config :as config]
    [prstack.github :as github]
    [prstack.pr :as pr]
    [prstack.stack :as stack]
    [prstack.system :as system]
    [prstack.ui :as ui]
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

(defn create-pr! [{:keys [vcs prs head-change base-change]}]
  (let [head-branch (:change/selected-branchname head-change)
        base-branch (:change/selected-branchname base-change)
        [prs err] prs
        ;; Find any PR for this head branch
        pr (when prs (pr/find-pr prs head-branch))
        ;; Check if the PR has the correct base branch
        correct-base? (and pr (= (:pr/base-branch pr) base-branch))]
    (if err
      (println (ansi/colorize :red (str "Error: " err)))
      (cond
        correct-base?
        (println
          (format "PR already exists for %s onto %s, skipping. (%s)\n"
            (ansi/colorize :blue head-branch)
            (ansi/colorize :blue base-branch)
            (ansi/colorize :gray (str "#" (:pr/number pr)))))

        pr
        (println
          (format "%s PR exists for %s but has wrong base branch: %s (expected: %s). Skipping.\n"
            (ansi/colorize :yellow "⚠")
            (ansi/colorize :blue head-branch)
            (ansi/colorize :yellow (:pr/base-branch pr))
            (ansi/colorize :blue base-branch)))

        :else
        (do
          (ansi/colorize :yellow "Checking remote branches")
          (when (ensure-remote-branch! vcs base-change "Base branch not pushed to remote. ")
            (when (ensure-remote-branch! vcs head-change "Head branch not pushed to remote. ")
              (prompt-and-create-prs! head-branch base-branch))))))))

(defn create-prs! [vcs {:keys [prs stacks]}]
  (if (seq stacks)
    (do
      (println (ansi/colorize :cyan "Let's create the PRs!\n"))
      (doseq [stack stacks]
        (doseq [[base-change head-change] (u/consecutive-pairs stack)]
          (create-pr!
            {:vcs vcs
             :prs prs
             :head-change head-change
             :base-change base-change}))))
    (println (ansi/colorize :cyan "No PRs to create"))))

;; TODO also check if branch is pushed before making PR
(def command
  {:name "create-prs"
   :description "Creates missing PRs in the current stack"
   :exec
   (fn create-prs-cmd [args global-opts]
     (let [ref (first args)
           system (system/new (config/read-global) (config/read-local) global-opts)
           vcs (:system/vcs system)
           stacks
           (if ref
             (stack/get-stacks system ref)
             (stack/get-current-stacks system))
           split-stacks
           (stack/split-feature-base-stacks stacks)
           prs (ui/fetch-prs-with-spinner)]
       (cli.ui/print-stacks split-stacks prs)
       (create-prs! vcs {:prs prs :stacks stacks})))})
