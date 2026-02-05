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
    [prstack.vcs :as vcs]
    [prstack.vcs.branch :as vcs.branch]))

(defn- ensure-remote-branch! [branchname msg vcs branch-infos]
  (let [branch-info (get branch-infos branchname)]
    (if (vcs.branch/no-remote? branch-info)
      (when (tty/prompt-confirm
              {:prompt (str msg (format "Push %s to remote?" (ansi/colorize :blue branchname)))})
        (vcs/push-branch! vcs branchname)
        true)
      true)))

(defn create-pr! [{:keys [head-change base-change prs vcs branch-infos]}]
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
        (when (tty/prompt-confirm
                {:prompt
                 (format "Create a PR for %s onto %s?"
                   (ansi/colorize :blue head-branch)
                   (ansi/colorize :blue base-branch))})
          (ui/info (ansi/colorize :yellow "Checking remote branches"))
          (when (ensure-remote-branch! base-branch "Base branch not pushed to remote. " vcs branch-infos)
            (when (ensure-remote-branch! head-branch "Head branch not pushed to remote. " vcs branch-infos)
              (github/create-pr! head-branch base-branch)
              (println (ansi/colorize :green "\n✅ Created PR ... \n")))))))))

(defn create-prs! [vcs {:keys [prs stacks vcs-graph]}]
  (if (seq stacks)
    (let [branch-infos (->> (vcs.branch/selected-branches-info vcs-graph)
                         (u/build-index :branch/branchname))]
      (println (ansi/colorize :cyan "Let's create the PRs!\n"))
      (doseq [stack stacks]
        (doseq [[base-change head-change] (u/consecutive-pairs stack)]
          (create-pr!
            {:head-change head-change
             :base-change base-change
             :prs prs
             :vcs vcs
             :branch-infos branch-infos}))))
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
           vcs-graph (vcs/read-graph vcs (:system/user-config system))
           stacks
           (if ref
             (stack/get-stacks system ref vcs-graph)
             (stack/get-current-stacks system vcs-graph))
           split-stacks
           (stack/split-feature-base-stacks stacks)
           prs (ui/fetch-prs-with-spinner)]
       (cli.ui/print-stacks split-stacks prs)
       (create-prs! vcs
         {:prs prs
          :stacks stacks
          :vcs-graph vcs-graph})))})
