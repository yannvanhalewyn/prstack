(ns prstack.cli.commands.sync
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [prstack.cli.commands.create-prs :as commands.create-prs]
    [prstack.cli.ui :as cli.ui]
    [prstack.config :as config]
    [prstack.github :as github]
    [prstack.stack :as stack]
    [prstack.system :as system]
    [prstack.ui :as ui]
    [prstack.vcs :as vcs]
    [prstack.vcs.graph :as vcs.graph]))

(defn parse-opts [args]
  {:all? (boolean (some #{"--all"} args))})

(comment
  (parse-opts ["--all"])
  (parse-opts []))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Merged PR handling

(defn find-merged-bookmarks
  "Finds local bookmarks whose PRs have been merged.
  Returns a set of bookmark names."
  [local-bookmarks merged-prs]
  (let [merged-branches (set (map :pr/head-branch merged-prs))]
    (set (filter merged-branches local-bookmarks))))

(defn cleanup-merged-bookmarks!
  "Prompts user to delete local bookmarks for merged PRs."
  [vcs merged-bookmarks]
  (when (seq merged-bookmarks)
    (println (ansi/colorize :yellow "\nThe following branches have merged PRs:"))
    (doseq [b (sort merged-bookmarks)]
      (println (str "  - " (ansi/colorize :cyan b))))
    (when (tty/prompt-confirm
            {:prompt "\nDelete these local bookmarks?"})
      (doseq [b merged-bookmarks]
        (println (format "Deleting bookmark %s..." (ansi/colorize :blue b)))
        (vcs/delete-bookmark! vcs b))
      (println (ansi/colorize :green "Done.")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Base branch syncing (trunk + feature bases)

(defn get-base-branch-status
  "Returns the status of a base branch (trunk or feature-base).
  Uses the vcs-graph to check ancestry relationships.
  Returns a map with :branch, :local-ref, :remote-ref, and :status where status is one of:
    :in-sync     - local and remote are the same
    :pull-needed - remote is ahead, local should be updated to remote
    :push-needed - local is ahead, should be pushed to remote
    :diverged    - both have changes, needs manual resolution"
  [vcs vcs-graph branch-name]
  (try
    (let [local-ref (vcs/get-change-id vcs branch-name)
          remote-ref (vcs/get-change-id vcs (str branch-name "@origin"))
          status (cond
                   (= local-ref remote-ref)
                   :in-sync

                   (vcs.graph/is-ancestor? vcs-graph local-ref remote-ref)
                   :pull-needed

                   (vcs.graph/is-ancestor? vcs-graph remote-ref local-ref)
                   :push-needed

                   :else
                   :diverged)]
      {:branch branch-name
       :local-ref local-ref
       :remote-ref remote-ref
       :status status})
    (catch Exception _
      ;; Branch might not exist on remote
      {:branch branch-name
       :local-ref nil
       :remote-ref nil
       :status :in-sync})))

(defn sync-base-branches!
  "Syncs all base branches (trunk + feature bases) with remote.
  Handles three cases:
  - Remote ahead: offers to update local to match remote
  - Local ahead: offers to push local to remote
  - Diverged: warns user and skips (requires manual resolution)
  Returns a map of branch -> status info"
  [vcs vcs-graph trunk-branch feature-base-branches]
  (let [all-bases (cons trunk-branch feature-base-branches)
        statuses (map #(get-base-branch-status vcs vcs-graph %) all-bases)
        pull-needed (filter #(= :pull-needed (:status %)) statuses)
        push-needed (filter #(= :push-needed (:status %)) statuses)
        diverged (filter #(= :diverged (:status %)) statuses)]

    ;; Handle branches where remote is ahead (pull needed)
    (when (seq pull-needed)
      (println (ansi/colorize :yellow "\nThe following base branches have new changes on remote:"))
      (doseq [{:keys [branch]} pull-needed]
        (println (str "  - " (ansi/colorize :cyan branch))))
      (when (tty/prompt-confirm
              {:prompt "\nUpdate local branches to match remote?"})
        (doseq [{:keys [branch]} pull-needed]
          (println (format "Updating %s to remote..." (ansi/colorize :blue branch)))
          (vcs/set-bookmark-to-remote! vcs branch))
        (println (ansi/colorize :green "Done."))))

    ;; Handle branches where local is ahead (push needed)
    (when (seq push-needed)
      (println (ansi/colorize :yellow "\nThe following base branches have local changes not on remote:"))
      (doseq [{:keys [branch]} push-needed]
        (println (str "  - " (ansi/colorize :cyan branch))))
      (when (tty/prompt-confirm
              {:prompt "\nPush local changes to remote?"})
        (doseq [{:keys [branch]} push-needed]
          (println (format "Pushing %s to remote..." (ansi/colorize :blue branch)))
          (vcs/push-branch vcs branch))
        (println (ansi/colorize :green "Done."))))

    ;; Warn about diverged branches
    (when (seq diverged)
      (println (ansi/colorize :red "\nWarning: The following base branches have diverged (both local and remote have changes):"))
      (doseq [{:keys [branch]} diverged]
        (println (str "  - " (ansi/colorize :cyan branch))))
      (println (ansi/colorize :yellow "These branches need manual resolution.")))

    ;; Return status for use in rebase decisions
    (into {} (map (juxt :branch identity) statuses))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PR base branch fixes

(defn find-prs-with-wrong-base
  "Finds PRs whose base branch is a merged branch (no longer exists as open PR).
  Returns a seq of {:pr pr, :expected-base branch-name}"
  [open-prs merged-bookmarks trunk-branch feature-base-branches]
  (let [valid-bases (set (cons trunk-branch feature-base-branches))
        open-branches (set (map :pr/head-branch open-prs))]
    (for [pr open-prs
          :let [base (:pr/base-branch pr)]
          ;; Base is wrong if it's a merged branch (not in open PRs and not a valid base)
          :when (and (not (valid-bases base))
                     (not (open-branches base))
                     (or (merged-bookmarks base)
                         ;; Or base branch simply doesn't exist locally anymore
                         true))]
      {:pr pr
       ;; The expected base is the trunk or feature-base that this stack is based on
       ;; For simplicity, default to trunk. A more sophisticated approach would
       ;; trace the stack to find the correct base.
       :expected-base trunk-branch})))

(defn fix-pr-bases!
  "Prompts user to fix PRs with wrong base branches on GitHub."
  [prs-with-wrong-base]
  (when (seq prs-with-wrong-base)
    (println (ansi/colorize :yellow "\nThe following PRs have outdated base branches:"))
    (doseq [{:keys [pr expected-base]} prs-with-wrong-base]
      (println (format "  - #%d %s (base: %s -> %s)"
                 (:pr/number pr)
                 (:pr/title pr)
                 (ansi/colorize :red (:pr/base-branch pr))
                 (ansi/colorize :green expected-base))))
    (when (tty/prompt-confirm
            {:prompt "\nUpdate PR base branches on remote?"})
      (doseq [{:keys [pr expected-base]} prs-with-wrong-base]
        (println (format "Updating PR #%d base to %s..."
                   (:pr/number pr)
                   (ansi/colorize :blue expected-base)))
        (github/update-pr-base! (:pr/number pr) expected-base))
      (println (ansi/colorize :green "Done.")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rebasing

(defn find-stack-base
  "Finds the base branch for a stack (trunk or feature-base)."
  [stack]
  (let [base-change (first stack)]
    (when (#{:trunk :feature-base} (:change/type base-change))
      (:change/selected-branchname base-change))))

(defn offer-rebase!
  "Offers to rebase the current stack onto its base branch if the base has moved."
  [vcs base-statuses stacks]
  (let [;; Find unique bases from all stacks
        stack-bases (->> stacks
                      (map find-stack-base)
                      (remove nil?)
                      distinct)
        ;; Find which bases have moved
        moved-bases (filter #(get-in base-statuses [% :moved?]) stack-bases)]
    (when (seq moved-bases)
      (let [;; For now, just offer to rebase onto the first moved base
            ;; A more sophisticated approach would handle multiple stacks separately
            target-base (first moved-bases)]
        (when (tty/prompt-confirm
                {:prompt (format "\nRebase onto %s?" (ansi/colorize :blue target-base))})
          (vcs/rebase-on! vcs target-base))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main sync command

(def command
  {:name "sync"
   :flags [["--all" "-a" "Looks all your stacks, not just the current one"]]
   :description "Syncs the current stack with the remote"
   :exec
   (fn sync [args global-opts]
     (let [opts (parse-opts args)
           local-config (config/read-local)
           system (system/new (config/read-global) local-config global-opts)
           vcs (:system/vcs system)
           trunk-branch (vcs/trunk-branch vcs)
           feature-base-branches (:feature-base-branches local-config)]

       ;; Step 1: Fetch from remote
       (println (ansi/colorize :yellow "\nFetching from remote..."))
       (vcs/fetch! vcs)

       ;; Step 2: Get merged PRs and find bookmarks to clean up
       (println (ansi/colorize :yellow "\nChecking for merged PRs..."))
       (let [[merged-prs _err] (github/list-prs vcs {:state :merged})
             local-bookmarks (vcs/list-local-bookmarks vcs)
             merged-bookmarks (find-merged-bookmarks local-bookmarks merged-prs)]

         ;; Step 3: Clean up merged bookmarks
         (cleanup-merged-bookmarks! vcs merged-bookmarks)

         ;; Step 4: Sync all base branches (trunk + feature bases)
         ;; Read the graph first to check ancestry relationships
         (println (ansi/colorize :yellow "\nChecking base branches..."))
         (let [vcs-graph (vcs/read-graph vcs (:system/user-config system))
               base-statuses (sync-base-branches! vcs vcs-graph trunk-branch feature-base-branches)
               ;; Step 5: Re-read stacks with clean state (graph may have changed after sync)
               stacks (if (:all? opts)
                        (stack/get-all-stacks system)
                        (stack/get-current-stacks system))
               ;; Step 6: Check for PRs with wrong base branches
               [open-prs _err :as prs-result] (ui/fetch-prs-with-spinner)
               prs-with-wrong-base (find-prs-with-wrong-base open-prs
                                     merged-bookmarks trunk-branch feature-base-branches)
               split-stacks (stack/split-feature-base-stacks stacks)]

           (fix-pr-bases! prs-with-wrong-base)

           ;; Step 7: Offer rebase if bases moved
           (offer-rebase! vcs base-statuses stacks)

           ;; Step 8: Push tracked branches
           (println (ansi/colorize :yellow "\nPushing local tracked branches..."))
           (vcs/push-tracked! vcs)
           (println)

           ;; Step 9: Display stacks and offer to create missing PRs
           (cli.ui/print-stacks split-stacks prs-result)
           (doseq [stack stacks]
             (println "Syncing stack:"
               (ansi/colorize :blue
                 (first (:change/local-branchnames (last stack)))))
             (if (> (count stack) 1)
               (when (tty/prompt-confirm
                       {:prompt "Would you like to create missing PRs?"})
                 (commands.create-prs/create-prs! vcs {:prs prs-result :stack stack}))
               (println "No missing PRs to create."))
             (println))))))})
