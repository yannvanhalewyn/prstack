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

(defn- ensure-remote-branch [change msg]
  (or (vcs/remote-branchname change)
      (let [branchname (vcs/local-branchname change)]
        (when (tty/prompt-yes (str msg (format " Push %s?" (ansi/colorize :blue branchname))))
          (vcs/push-branch branchname)
          true))))

(defn- prompt-and-create-prs! [head-branch base-branch]
  (when (tty/prompt-yes
          (format "Create a PR for %s onto %s?"
            (ansi/colorize :blue head-branch)
            (ansi/colorize :blue base-branch)))
    (github/create-pr! head-branch base-branch)
    (println (ansi/colorize :green "\n✅ Created PR ... \n"))))

(defn create-prs [{:keys [stack]}]
  (if (seq stack)
    (do
      (println (ansi/colorize :cyan "Let's create the PRs!\n"))
      (doseq [[cur-change next-change] (u/consecutive-pairs stack)]
        (let [head-branch (vcs/local-branchname next-change)
              base-branch (vcs/local-branchname cur-change)
              pr (github/find-pr head-branch base-branch)]
          (if pr
            (println
              (format "PR already exists for %s onto %s, skipping. (%s)"
                (ansi/colorize :blue head-branch)
                (ansi/colorize :blue base-branch)
                (ansi/colorize :gray (str "#" (:pr/number pr)))))
            (do
              (ansi/colorize :yellow "Checking remote branches")
              (when (ensure-remote-branch cur-change "Base branch not pushed to remote.")
                (when (ensure-remote-branch next-change "Head branch not pushed to remote.")
                  (prompt-and-create-prs! head-branch base-branch))))))))
    (println (ansi/colorize :cyan "No PRs to create"))))

;; TODO also check if branched is pushed before making PR
(def command
  {:name "create"
   :description "Creates missing PRs in the current stack"
   :exec
   (fn create-prs-cmd [args]
     (let [ref (first args)
           config (config/read-local)
           vcs-config (vcs/config)
           stack
           (if ref
             (stack/get-stack ref vcs-config)
             (first (stack/get-current-stacks vcs-config config)))
           processed-stacks
           (stack/process-stacks-with-feature-bases vcs-config config [stack])]
       (ui/print-stacks processed-stacks vcs-config config {:include-prs? true})
       (create-prs {:stack stack})))})
