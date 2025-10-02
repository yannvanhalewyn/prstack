(ns prstack.commands.create-prs
  (:require
    [prstack.stack :as stack]
    [prstack.tty2 :as tty]
    [prstack.ui :as ui]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn- ensure-remote-branch [change msg]
  (or (vcs/remote-branchname change)
      (let [branchname (vcs/local-branchname change)]
        (when (tty/prompt-yes (str msg (format " Push %s?" (u/colorize :blue branchname))))
          (vcs/push-branch branchname)
          true))))

(defn- prompt-and-create-prs! [head-branch base-branch]
  (when (tty/prompt-yes
          (format "Create a PR for %s onto %s?"
            (u/colorize :blue head-branch)
            (u/colorize :blue base-branch)))
    (vcs/create-pr! head-branch base-branch)
    (println (u/colorize :green "\n✅ Created PR ... \n"))))

(defn create-prs [{:keys [stack]}]
  (if (seq stack)
    (do
      (println (u/colorize :cyan "Let's create the PRs!\n"))
      (doseq [[cur-change next-change] (u/consecutive-pairs stack)]
        (let [head-branch (vcs/local-branchname next-change)
              base-branch (vcs/local-branchname cur-change)
              pr (vcs/find-pr head-branch base-branch)]
          (if pr
            (println
              (format "PR already exists for %s onto %s, skipping. (%s)"
                (u/colorize :blue head-branch)
                (u/colorize :blue base-branch)
                (u/colorize :gray (str "#" (:pr/number pr)))))
            (do
              (u/colorize :yellow "Checking remote branches")
              (when (ensure-remote-branch cur-change "Base branch not pushed to remote.")
                (when (ensure-remote-branch next-change "Head branch not pushed to remote.")
                  (prompt-and-create-prs! head-branch base-branch))))))))
    (println (u/colorize :cyan "No PRs to create"))))

;; TODO also check if branched is pushed before making PR
(def command
  {:name "create"
   :description "Creates missing PRs in the current stack"
   :exec
   (fn create-prs-cmd [args]
     (let [ref (first args)
           vcs-config (vcs/config)
           stack
           (if ref
             (stack/get-stack ref vcs-config)
             (first (stack/get-current-stacks vcs-config)))]
       (ui/print-stacks [stack] vcs-config {:include-prs? true})
       (create-prs {:stack stack})))})
