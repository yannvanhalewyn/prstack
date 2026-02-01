(ns prstack.cli.commands.sync
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [clojure.set :as set]
    [prstack.cli.commands.create-prs :as commands.create-prs]
    [prstack.cli.ui :as cli.ui]
    [prstack.config :as config]
    [prstack.github :as github]
    [prstack.stack :as stack]
    [prstack.system :as system]
    [prstack.ui :as ui]
    [prstack.vcs :as vcs]
    [prstack.vcs.branch :as vcs.branch]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UI Helpers (TODO move to some UI namespace)

(defn- ui-header [title]
  (println)
  (println (ansi/colorize :blue (str "▸ " title))))

(defn- ui-info [& parts]
  (println (str "  " (apply str parts))))

(defn- ui-success [msg]
  (println (str "  " (ansi/colorize :green "✓") " " msg)))

(defn- ui-branch [name]
  (ansi/colorize :cyan name))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 1: Fetch

(defn- fetch! [vcs]
  (ui-header "Fetching from remote")
  (vcs/fetch! vcs)
  (ui-success "Done"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 2: Cleanup merged branches

(defn- cleanup-merged-branches! [vcs]
  (ui-header "Checking for merged PRs")
  (let [[merged-prs] (github/list-prs vcs {:state :merged})
        local-bookmarks (set (vcs/list-local-bookmarks vcs))
        merged-branches (set (map :pr/head-branch merged-prs))
        to-delete (set/intersection local-bookmarks merged-branches)]
    (if (empty? to-delete)
      (ui-success "No merged branches to clean up")
      (do
        (ui-info "Local branches with merged PRs:")
        (doseq [b (sort to-delete)]
          (ui-info "  • " (ui-branch b)))
        (println)
        (when (tty/prompt-confirm {:prompt "  Delete these branches?"})
          (doseq [b to-delete]
            (ui-info "Deleting " (ui-branch b) "...")
            (vcs/delete-bookmark! vcs b))
          (ui-success "Cleanup complete"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 3: Sync base branches

(defn- sync-base-branches!
  "Syncs base branches. Returns set of branches that were pulled."
  [system]
  (ui-header "Syncing base branches")
  (let [vcs (:system/vcs system)
        vcs-graph (vcs/read-graph vcs (:system/user-config system))
        selected-branches (vcs.branch/selected-branches-info vcs-graph)]

    ;; Show status for all branches
    (doseq [branch selected-branches]
      (let [branchname (:branch/branchname branch)]
        (case (:branch/status branch)
          :branch.status/no-remote (ui-info (ansi/colorize :yellow "•") " " (ui-branch branchname) (ansi/colorize :yellow " no remote"))
          :branch.status/up-to-date (ui-info (ansi/colorize :green "✓") " " (ui-branch branchname) (ansi/colorize :gray " up to date"))
          :branch.status/ahead (ui-info (ansi/colorize :yellow "↑") " " (ui-branch branchname) (ansi/colorize :yellow " local ahead"))
          :branch.status/behind (ui-info (ansi/colorize :yellow "↓") " " (ui-branch branchname) (ansi/colorize :yellow " remote ahead"))
          :branch.status/diverged (ui-info (ansi/colorize :red "⚠") " " (ui-branch branchname) (ansi/colorize :red " diverged")))))

    (let [pulled (atom #{})]
      (doseq [{:keys [branch/branchname]} (filter vcs.branch/behind? selected-branches)]
        (println)
        (when (tty/prompt-confirm {:prompt (format "Pull %s?" branchname)})
          (ui-info "Pulling " (ui-branch branchname) "...")
          (vcs/set-bookmark-to-remote! vcs branchname)
          (ui-success "Pulled")
          (swap! pulled conj branchname)))

      (doseq [{:keys [branch/branchname]} (filter vcs.branch/ahead? selected-branches)]
        (println)
        (when (tty/prompt-confirm {:prompt (format "Push %s?" branchname)})
          (ui-info "Pushing " (ui-branch branchname) "...")
          (vcs/push-branch! vcs branchname)
          (ui-success "Pushed")))

      (doseq [{:keys [branch/branchname]} (filter vcs.branch/diverged? selected-branches)]
        (println)
        (let [solution
              (tty/prompt-pick
                {:prompt (str "Branch " (ui-branch branchname) " diverged. What do you want to do?")
                 :options ["Push local to remote" "Set local to remote" "Do nothing"]})]
          (case solution
            "Push local to remote"
            (do
              (ui-info "Pushing " (ui-branch branchname) "...")
              (vcs/push-branch! vcs branchname)
              (ui-success "Pushed"))
            "Set local to remote"
            (do
              (ui-info "Setting " (ui-branch branchname) " to remote...")
              (vcs/set-bookmark-to-remote! vcs branchname)
              (ui-success (str "Set local " (ui-branch branchname) " to remote revision")))
            nil)))

      ;; Return pulled branches for rebase decision
      @pulled)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 4: Offer rebase

(defn- offer-rebase! [vcs pulled-branches current-stacks]
  (when (seq pulled-branches)
    (ui-header "Rebase")
    (let [stack-bases (->> current-stacks
                        (map first)
                        (keep :change/selected-branchname)
                        set)
          affected-bases (set/intersection stack-bases pulled-branches)]
      (if (empty? affected-bases)
        (ui-info (ansi/colorize :gray "Current stack not affected"))
        (let [base (first affected-bases)]
          (ui-info "Base " (ui-branch base) " was updated")
          (println)
          (when (tty/prompt-confirm {:prompt (str "  Rebase onto " base "?")})
            (ui-info "Rebasing...")
            (vcs/rebase-on! vcs base)
            (ui-success "Rebased")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 5: Show status & create PRs

(defn- show-status-and-create-prs! [vcs system opts]
  (ui-header "Stack status")
  (println)
  (let [stacks (if (:all? opts)
                 (stack/get-all-stacks system)
                 (stack/get-current-stacks system))
        split-stacks (stack/split-feature-base-stacks stacks)
        [prs] (ui/fetch-prs-with-spinner)]

    (cli.ui/print-stacks split-stacks [prs])

    ;; Offer to create missing PRs
    (doseq [s stacks]
      (let [leaf-branch (:change/selected-branchname (last s))
            has-missing-prs? (and (> (count s) 1)
                                  (some (fn [change]
                                          (let [b (:change/selected-branchname change)]
                                            (and b
                                                 (not= (:change/type change) :trunk)
                                                 (not= (:change/type change) :feature-base)
                                                 (not (some #(= b (:pr/head-branch %)) prs)))))
                                    s))]
        (when has-missing-prs?
          (ui-info "Stack " (ui-branch leaf-branch) " has branches without PRs")
          (when (tty/prompt-confirm {:prompt "  Create missing PRs?"})
            (commands.create-prs/create-prs! vcs {:prs prs :stack s})))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main command

(defn- parse-opts [args]
  {:all? (boolean (some #{"--all"} args))})

(def command
  {:name "sync"
   :description "Sync local state with remote"
   :flags [["--all" "-a" "Sync all stacks, not just current"]]
   :exec
   (fn [args global-opts]
     (let [opts (parse-opts args)
           local-config (config/read-local)
           system (system/new (config/read-global) local-config global-opts)
           vcs (:system/vcs system)]

       ;; Step 1: Fetch
       (fetch! vcs)

       ;; Step 2: Cleanup merged branches
       (cleanup-merged-branches! vcs)

       ;; Step 3: Sync base branches
       (let [pulled-branches (sync-base-branches! system)
             current-stacks (stack/get-current-stacks system)]
         ;; Step 4: Offer rebase if base was pulled
         (offer-rebase! vcs pulled-branches current-stacks))

       ;; Step 5: Show status & create PRs
       (show-status-and-create-prs! vcs system opts)

       (println)
       (println (ansi/colorize :green "✓ Sync complete"))))})
