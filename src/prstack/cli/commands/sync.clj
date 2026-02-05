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
    [prstack.vcs.branch :as vcs.branch]
    [prstack.vcs.graph :as vcs.graph]))

(defn- read-vcs-graph [system]
  (vcs/read-graph (:system/vcs system) (:system/user-config system)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UI Helpers (TODO move to some UI namespace)

(defn- ui-header [title]
  (println)
  (println (ansi/colorize :blue (str "▸ " title))))

(defn- ui-error [err]
  (println (str "  " (ansi/colorize :red "Error:") " " err)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 1: Fetch

(defn- fetch! [vcs]
  (ui-header "Fetching from remote")
  (vcs/fetch! vcs)
  (ui/success "Done"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 2: Cleanup merged branches

(defn- cleanup-merged-branches! [system _opts]
  (ui-header "Checking for merged PRs")
  (let [vcs (:system/vcs system)
        user-config (:system/user-config system)
        vcs-graph (vcs/read-graph vcs user-config)
        [merged-prs err] (github/list-prs vcs {:state :merged})
        local-bookmarks (set (vcs.graph/all-selected-branchnames vcs-graph))
        to-delete (filter #(contains? local-bookmarks (:pr/head-branch %)) merged-prs)]
    (cond
      err (ui-error (:error/message err))
      (empty? to-delete)
      (ui/success "No merged branches to clean up")
      :else
      (do
        (ui/info "Local branches with merged PRs:")
        (doseq [pr (sort-by :pr/head-branch to-delete)]
          (ui/info (format "  • %s was merged into %s (PR #%s)"
                     (ui/format-branch (:pr/head-branch pr))
                     (ui/format-branch (:pr/base-branch pr))
                     (:pr/number pr))))
        (println)
        (doseq [pr to-delete]
          (when (tty/prompt-confirm {:prompt (str "Delete " (ui/format-branch (:pr/head-branch pr)))})
            (ui/info "Deleting " (ui/format-branch (:pr/head-branch pr)) "...")
            (vcs/delete-bookmark! vcs (:pr/head-branch pr))
            (ui/success "Branch deleted")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 3: Sync base branches

(defn- sync-branches!
  "Syncs branches by either pushing or pulling them, according to their status.
  Returns the set of branches that were pulled."
  [system]
  (ui-header "Syncing branches")
  (let [vcs (:system/vcs system)
        vcs-graph (read-vcs-graph system)
        selected-branches (vcs.branch/selected-branches-info vcs-graph)]

    ;; Show status for all branches
    (doseq [branch selected-branches]
      (let [branchname (:branch/branchname branch)]
        (case (:branch/status branch)
          :branch.status/no-remote (ui/info (ansi/colorize :yellow "•") " " (ui/format-branch branchname) (ansi/colorize :yellow " no remote"))
          :branch.status/up-to-date (ui/info (ansi/colorize :green "✓") " " (ui/format-branch branchname) (ansi/colorize :gray " up to date"))
          :branch.status/ahead (ui/info (ansi/colorize :yellow "↑") " " (ui/format-branch branchname) (ansi/colorize :yellow " local ahead"))
          :branch.status/behind (ui/info (ansi/colorize :yellow "↓") " " (ui/format-branch branchname) (ansi/colorize :yellow " remote ahead"))
          :branch.status/diverged (ui/info (ansi/colorize :red "⚠") " " (ui/format-branch branchname) (ansi/colorize :red " diverged")))))

    (let [pulled (atom #{})]
      (doseq [{:keys [branch/branchname]} (filter vcs.branch/behind? selected-branches)]
        (println)
        (when (tty/prompt-confirm {:prompt (format "Pull %s?" (ui/format-branch branchname))})
          (ui/info "Pulling " (ui/format-branch branchname) "...")
          (vcs/set-bookmark-to-remote! vcs branchname)
          (ui/success "Pulled")
          (swap! pulled conj branchname)))

      (doseq [{:keys [branch/branchname]} (filter vcs.branch/ahead? selected-branches)]
        (println)
        (when (tty/prompt-confirm {:prompt (format "Push %s?" (ui/format-branch branchname))})
          (ui/info "Pushing " (ui/format-branch branchname) "...")
          (vcs/push-branch! vcs branchname)
          (ui/success "Pushed")))

      (doseq [{:keys [branch/branchname]} (filter vcs.branch/diverged? selected-branches)]
        (println)
        (let [solution
              (tty/prompt-pick
                {:prompt (str "Branch " (ui/format-branch branchname) " diverged. What do you want to do?")
                 :options
                 [{:name "Push local to remote" :action :push}
                  {:name "Set local to remote" :action :pull}
                  {:name "Do nothing"}]
                 :render-option :name})]
          (case (:action solution)
            :push
            (do
              (ui/info "Pushing " (ui/format-branch branchname) "...")
              (vcs/push-branch! vcs branchname)
              (ui/success "Pushed"))
            :pull
            (do
              (ui/info "Setting " (ui/format-branch branchname) " to remote...")
              (vcs/set-bookmark-to-remote! vcs branchname)
              (ui/success (str "Set local " (ui/format-branch branchname) " to remote revision")))
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
        (ui/info (ansi/colorize :gray "Current stack not affected"))
        (let [base (first affected-bases)]
          (ui/info "Base " (ui/format-branch base) " was updated")
          (println)
          (when (tty/prompt-confirm {:prompt (str "Rebase onto " base "?")})
            (ui/info "Rebasing...")
            (vcs/rebase-on! vcs base)
            (ui/success "Rebased")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step 5: Show status & create PRs

(defn- show-status-and-create-prs! [system opts]
  (ui-header "Stack status")
  (println)
  (let [vcs (:system/vcs system)
        vcs-graph (vcs/read-graph vcs (:system/user-config system))
        stacks (if (:all? opts)
                 (stack/get-all-stacks system vcs-graph)
                 (stack/get-current-stacks system vcs-graph))
        split-stacks (stack/split-feature-base-stacks stacks)
        [prs _err :as pr-result] (ui/fetch-prs-with-spinner)]

    (cli.ui/print-stacks split-stacks [prs])

    ;; Offer to create missing PRs
    (doseq [stack stacks]
      (let [leaf-branch (:change/selected-branchname (last stack))
            has-missing-prs? (and (> (count stack) 1)
                                  (some (fn [change]
                                          (let [branchname (:change/selected-branchname change)]
                                            (and branchname
                                                 (not= (:change/type change) :trunk)
                                                 (not= (:change/type change) :feature-base)
                                                 (not (some #(= (:pr/head-branch %) branchname) prs)))))
                                    stack))]
        (when has-missing-prs?
          (ui/info "Stack " (ui/format-branch leaf-branch) " has branches without PRs")
          (when (tty/prompt-confirm {:prompt "  Create missing PRs?"})
            (commands.create-prs/create-prs! vcs
              {:prs pr-result
               :stacks [stack]
               :vcs-graph vcs-graph})))))))

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
       (cleanup-merged-branches! system opts)

       ;; Step 3: Sync base branches
       (let [pulled-branches (sync-branches! system)
             current-stacks (stack/get-current-stacks system)]
         ;; Step 4: Offer rebase if base was pulled
         (offer-rebase! vcs pulled-branches current-stacks))

       ;; Step 5: Show status & create PRs
       (show-status-and-create-prs! system opts)

       (println)
       (println (ansi/colorize :green "✓ Sync complete"))))})
