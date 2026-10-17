(ns prstack.tui.db
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [bb-tty.tui :as tui]
    [clojure.java.browse :as browse]
    [clojure.string :as str]
    [prstack.cli.commands.create-prs :as commands.create-prs]
    [prstack.cli.commands.sync :as commands.sync]
    [prstack.config :as config]
    [prstack.custom-command :as custom-command]
    [prstack.pr :as pr]
    [prstack.remote :as remote]
    [prstack.repo :as repo]
    [prstack.stack :as stack]
    [prstack.tools.schema :as tools.schema]
    [prstack.ui :as ui]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]
    [prstack.vcs.branch :as vcs.branch]
    [prstack.workspace :as workspace]))

(defn- load-schema
  "Data that is loaded in the background. While reloading, the previous result
  is kept so it can still be displayed."
  [result-schema]
  [:map
   [:load/status [:enum :status/idle :status/pending :status/failed :status/success]]
   [:load/result {:optional true} result-schema]
   [:load/error {:optional true}
    [:map
     [:error/type {:optional true} :keyword]
     [:error/message :string]]]])

(def ^:lsp/allow-unused RepoState
  "A repo as held in the app state: the repo and the data loaded through it.
  The repo's clients are only present once it's opened, see `start-loader!`."
  (tools.schema/merge
    repo/Repo
    [:map
     ;; Results of earlier loads are discarded, see `update-loading-repo`
     [:repo/load-token :int]
     [:repo/graph (load-schema :map)]
     [:repo/current-stacks (load-schema [:sequential stack/Stack])]
     ;; Only loaded when displayed, see `ensure-all-stacks!`
     [:repo/all-stacks (load-schema [:sequential stack/Stack])]
     [:repo/prs (load-schema [:sequential remote/PR])]]))

(def ^:lsp/allow-unused RowKey
  "Identifies a selectable row. A branch can appear in multiple stacks, and
  stacks of different repos can have the same branches, so all three are
  needed."
  [:map
   [:repo/id :string]
   [:stack/id [:vector :string]]
   [:branch :string]])

(def ^:lsp/allow-unused AppState
  [:map
   ;; CLI options the TUI was started with, like `:project-dir`
   [:app-state/global-opts :map]

   ;; Config shared by all repos
   [:app-state/global-config config/GlobalConfig]

   ;; Repos keyed by `:repo/id`
   [:app-state/repos [:map-of :string RepoState]]

   ;; UI
   [:app-state/selected-tab :int]
   ;; Nil, or no longer displayed, selects a fallback row. See `selected-row`.
   [:app-state/selection {:optional true} RowKey]
   ;; Runs after closing the TUI, then only the repo is refreshed
   [:app-state/run-in-fg {:optional true}
    [:map
     [:fg/run :fn]
     [:fg/repo-id :string]]]])

(defonce app-state
  (atom {:app-state/selected-tab 0}))

(defn global-opts [state]
  (:app-state/global-opts state))

(defn global-config [state]
  (:app-state/global-config state))

(defn repos
  "Returns the repos in display order."
  [state]
  (sort-by :repo/name (vals (:app-state/repos state))))

(defn- repo-specs
  "Returns the id and name of every repository shown in the TUI: all
  repositories in the workspace, or else the one at the project dir."
  [state]
  (let [{:keys [workspace-dir project-dir]} (global-opts state)]
    (if workspace-dir
      (mapv #(workspace/repo-info workspace-dir %)
        (workspace/find-repos workspace-dir))
      (let [repo-id (repo/id project-dir)]
        [{:repo/id repo-id
          :repo/name (repo/default-name repo-id)}]))))

(defn- repo-opts
  "Returns the global opts for running a CLI command on `repo`."
  [state repo]
  (assoc (global-opts state) :project-dir (repo/project-dir repo)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Display rows
;;
;; The UI displays and navigates a flat list of rows across all repos. Rows
;; are derived from the repos and the selected tab, and carry everything needed
;; to render them and to act on them.

(defn- sort-stacks-by-base
  "Sorts stacks with non-feature-base stacks first, then feature-base stacks
   alphabetically by base branch name."
  [stacks]
  (let [partition-fn (fn [stack]
                       (let [base-change (first stack)
                             base-type (:change/type base-change)]
                         (if (= base-type :feature-base)
                           [:feature-base (:change/selected-branchname base-change)]
                           [:non-feature-base ""])))
        grouped (group-by (comp first partition-fn) stacks)
        non-feature-base (get grouped :non-feature-base [])
        feature-base (get grouped :feature-base [])
        sorted-feature-base (sort-by (comp second partition-fn) feature-base)]
    (concat non-feature-base sorted-feature-base)))

(defn- shows-all-stacks? [state]
  (not= 0 (:app-state/selected-tab state)))

(defn- tab-stacks-load
  "Returns the load of the stacks of `repo` displayed on the selected tab."
  [state repo]
  (if (shows-all-stacks? state)
    (:repo/all-stacks repo)
    (:repo/current-stacks repo)))

(defn- split-tab-stacks
  "Splits stacks into `:regular-stacks` and `:feature-base-stacks`. For the
  'My Stacks' tab, stacks are sorted with non-feature-base stacks first, then
  feature-base stacks alphabetically."
  [state stacks]
  (stack/split-feature-base-stacks
    (if (= 1 (:app-state/selected-tab state))
      (sort-stacks-by-base stacks)
      stacks)))

(defn- stack-rows
  "Returns a row for every change in `stack` from leaf to base, where the base
  change is not selectable."
  [repo stack]
  (let [repo-id (:repo/id repo)
        stack-id (stack/id stack)
        prs-load (:repo/prs repo)
        prs (:load/result prs-load)
        changes (reverse stack)]
    (concat
      (for [[change base] (u/consecutive-pairs changes)]
        (let [branch (:change/selected-branchname change)
              base-branch (:change/selected-branchname base)
              pr (when prs (pr/find-pr prs branch))]
          {:row/kind :change
           :row/key {:repo/id repo-id :stack/id stack-id :branch branch}
           :repo/id repo-id
           :change change
           :base base
           :pr pr
           :prs-load prs-load
           ;; Set when the PR has the wrong base branch
           :wrong-base-branch (when (and pr (not= (:pr/base-branch pr) base-branch))
                                base-branch)}))
      [{:row/kind :base
        :repo/id repo-id
        :change (last changes)}])))

(defn- stacks-rows [state repo stacks]
  (let [{:keys [regular-stacks feature-base-stacks]} (split-tab-stacks state stacks)]
    (concat
      ;; When every stack has only the trunk node, that means no stacks
      (if (stack/any-segments? regular-stacks)
        (mapcat
          (fn [[i stack]]
            (concat
              ;; TODO better detect current stack in megamerges for example
              [{:row/kind :stack-header
                :label (if (zero? i) "Current Stack" "Other Stack")
                :size (dec (count stack))}]
              (stack-rows repo stack)
              ;; Add blank line between stacks
              (when-not (and (= i (dec (count regular-stacks)))
                             (empty? feature-base-stacks))
                [{:row/kind :blank}])))
          (u/indexed regular-stacks))
        [{:row/kind :text :style :cyan :text "No stacks detetected"}
         {:row/kind :text :text "Create some branches to see some stacks appear."}
         {:row/kind :blank}])
      (when (seq feature-base-stacks)
        (concat
          [{:row/kind :section-header :text " Feature Base Branches"}]
          (mapcat #(concat (stack-rows repo %) [{:row/kind :blank}])
            feature-base-stacks))))))

(defn- repo-rows [state repo]
  (let [{:load/keys [status result error]} (tab-stacks-load state repo)]
    (cond
      ;; Also while reloading, as long as there is a previous result
      (or (= :status/success status) (some? result))
      (stacks-rows state repo (or result []))

      (= :status/failed status)
      [{:row/kind :error :text (:error/message error)}
       {:row/kind :blank}]

      :else
      [{:row/kind :loading}
       {:row/kind :blank}])))

(defn- repo-display
  "Returns how `repo` is displayed among multiple repos, based on the stacks
  displayed on the selected tab:
    :repo.display/stacks  - under a header with its stacks
    :repo.display/failed  - under a header with the error
    :repo.display/empty   - listed with the other repos without stacks
    :repo.display/loading - listed with the other repos that are loading

  While reloading, the previous result decides, so repos don't move around."
  [state repo]
  (let [{:load/keys [status result]} (tab-stacks-load state repo)]
    (cond
      (or (= :status/success status) (some? result))
      (let [{:keys [regular-stacks feature-base-stacks]}
            (split-tab-stacks state (or result []))]
        (if (or (stack/any-segments? regular-stacks) (seq feature-base-stacks))
          :repo.display/stacks
          :repo.display/empty))

      (= :status/failed status) :repo.display/failed
      :else :repo.display/loading)))

(defn- multiple-repo-rows
  "Returns the rows for multiple repos. Repos with stacks or that failed to load
  are shown under a header. Repos without stacks and repos that are still
  loading are each listed on a single line at the bottom, so repos only get a
  header once there is something to show."
  [state repos]
  (let [{stacks-repos :repo.display/stacks
         failed-repos :repo.display/failed
         empty-repos :repo.display/empty
         loading-repos :repo.display/loading}
        (group-by #(repo-display state %) repos)
        names #(str/join ", " (map :repo/name %))]
    (concat
      (mapcat
        (fn [repo]
          (let [rows (repo-rows state repo)]
            (concat
              [{:row/kind :repo-header
                :repo/id (:repo/id repo)
                :repo/name (:repo/name repo)}
               {:row/kind :blank}]
              rows
              ;; Separate repos with a blank line
              (when-not (= :blank (:row/kind (last rows)))
                [{:row/kind :blank}]))))
        ;; Keep the display order
        (filter (set (concat stacks-repos failed-repos)) repos))
      (when (seq empty-repos)
        [{:row/kind :text
          :style :gray
          :text (str "No stacks in: " (names empty-repos))}])
      (when (seq loading-repos)
        [{:row/kind :loading
          :repo-names (map :repo/name loading-repos)}]))))

(defn display-rows
  "Returns the rows displayed for all repos. Repo headers are only shown when
  there are multiple repos."
  [state]
  (let [repos (repos state)]
    (vec
      (if (> (count repos) 1)
        (multiple-repo-rows state repos)
        (mapcat #(repo-rows state %) repos)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Selection

(defn- selectable-rows [rows]
  (filterv #(= :change (:row/kind %)) rows))

(defn- resolve-selection
  "Returns the row of `selectable-rows` matching `selection`. The selected row
  can disappear after a refresh or a tab switch, so falls back to the same
  branch in another stack of the same repo, then to the first row of that repo
  and finally to the first row."
  [selectable-rows selection]
  (let [{repo-id :repo/id branch :branch} selection
        same-repo? #(= (:repo/id %) repo-id)]
    (or (u/find-first #(= (:row/key %) selection) selectable-rows)
        (u/find-first #(and (same-repo? %)
                            (= (get-in % [:row/key :branch]) branch))
          selectable-rows)
        (u/find-first same-repo? selectable-rows)
        (first selectable-rows))))

(defn selected-row
  "Returns the selected row, or nil when nothing can be selected. Pass `rows`
  when display rows were already computed."
  ([state]
   (selected-row state (display-rows state)))
  ([state rows]
   (resolve-selection (selectable-rows rows) (:app-state/selection state))))

(defn- selected-repo [state]
  (some->> (selected-row state) :repo/id (get (:app-state/repos state))))

(defn- target-repo
  "Returns the repo to act on for actions that don't need a selected change,
  like sync: the repo of the selected row, or else the first opened repo."
  [state]
  (or (selected-repo state)
      (u/find-first :repo/vcs (repos state))))

(defn- move-selection
  "Moves the selection `offset` rows down, staying within the rows."
  [state offset]
  (let [rows (selectable-rows (display-rows state))]
    (if-let [current (resolve-selection rows (:app-state/selection state))]
      (let [idx (u/find-index #(= (:row/key %) (:row/key current)) rows)
            new-idx (-> (+ idx offset) (max 0) (min (dec (count rows))))]
        (assoc state :app-state/selection (:row/key (nth rows new-idx))))
      state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Loading
;;
;; Every repo is loaded on its own thread, see `load-repos!`. Every load gets a
;; token, and loaders only write to their repo while that token is current, so
;; results of a load that was superseded by a refresh are dropped.

(defonce ^:private load-counter (atom 0))

(defn- load-status [load-map]
  (:load/status load-map))

(defn- succeeded [result]
  {:load/status :status/success :load/result result})

(defn- failed [error]
  {:load/status :status/failed :load/error error})

(defn- with-status
  "Sets the status of a load, keeping a previous result."
  [load-map status]
  (-> load-map
    (assoc :load/status status)
    (dissoc :load/error)))

(defn- run-load
  "Calls `f` and returns a load map with its result, or with its error when it
  throws."
  [f]
  (try
    (succeeded (f))
    (catch Throwable e
      (failed {:error/message (u/exception-message e)}))))

(defn- update-loading-repo
  "Updates the repo with `repo-id` if `token` is still its current load token."
  [state repo-id token f & args]
  (if (= token (get-in state [:app-state/repos repo-id :repo/load-token]))
    (apply update-in state [:app-state/repos repo-id] f args)
    state))

(defn- fetch-prs [repo]
  (let [[result err] (try
                       (remote/list-prs (:repo/remote repo) {})
                       (catch Throwable e
                         [nil {:error/message (u/exception-message e)}]))]
    (if err
      (failed err)
      (succeeded result))))

(defn- ensure-all-stacks!
  "Starts loading all stacks of the repo with `repo-id` when the selected tab
  displays them, its graph is loaded and they are not loaded or loading yet."
  [repo-id]
  (let [[old-state new-state]
        (swap-vals! app-state
          (fn [state]
            (let [repo (get-in state [:app-state/repos repo-id])]
              (if (and (shows-all-stacks? state)
                       (= :status/success (load-status (:repo/graph repo)))
                       (= :status/idle (load-status (:repo/all-stacks repo))))
                (update-in state [:app-state/repos repo-id :repo/all-stacks]
                  with-status :status/pending)
                state))))]
    (when-not (identical? old-state new-state)
      (let [repo (get-in new-state [:app-state/repos repo-id])
            token (:repo/load-token repo)
            vcs-graph (get-in repo [:repo/graph :load/result])]
        (u/start-large-stack-thread!
          (fn []
            (let [all-stacks (run-load #(stack/get-all-stacks repo vcs-graph))]
              (swap! app-state update-loading-repo repo-id token
                assoc :repo/all-stacks all-stacks))))))))

(defn- mark-loading
  "Marks the repo of `spec` as loading with `token`. Previous stacks are kept
  while reloading, so they are still displayed."
  [state {:repo/keys [id] :as spec} token]
  (update-in state [:app-state/repos id]
    (fn [repo]
      (merge repo spec
        {:repo/load-token token
         :repo/graph (with-status (:repo/graph repo) :status/pending)
         :repo/current-stacks (with-status (:repo/current-stacks repo) :status/pending)
         :repo/all-stacks (with-status (:repo/all-stacks repo) :status/idle)
         ;; Previous PRs are not kept, so rows show they're being fetched
         :repo/prs {:load/status :status/pending}}))))

(defn- start-loader!
  "Opens the repo of `spec` and loads its graph, stacks and PRs in the
  background, for the load with `token`. PRs are fetched in parallel as soon
  as the repo is opened. All stacks are only loaded when displayed."
  [{:repo/keys [id]} token]
  (let [update! (fn [f & args]
                  (apply swap! app-state update-loading-repo id token f args))]
    (u/start-large-stack-thread!
      (fn []
        (let [opened (run-load #(repo/open id))]
          (if (= :status/failed (load-status opened))
            (update! assoc
              :repo/graph opened
              :repo/current-stacks opened
              :repo/all-stacks opened
              :repo/prs opened)
            (let [repo (-> (:load/result opened)
                         ;; Keep the name of the spec
                         (dissoc :repo/name))]
              (update! merge repo)
              (u/start-large-stack-thread!
                #(update! assoc :repo/prs (fetch-prs repo)))
              (let [vcs-graph (run-load #(vcs/read-graph (:repo/vcs repo)
                                           (:repo/local-config repo)))]
                (if (= :status/failed (load-status vcs-graph))
                  (update! assoc
                    :repo/graph vcs-graph
                    :repo/current-stacks vcs-graph
                    :repo/all-stacks vcs-graph)
                  (let [current-stacks (run-load #(stack/get-current-stacks repo
                                                    (:load/result vcs-graph)))]
                    (update! assoc
                      :repo/graph vcs-graph
                      :repo/current-stacks current-stacks)
                    (ensure-all-stacks! id)))))))))))

(defn- load-repos!
  "Loads the repos of `specs`, see `start-loader!`. All repos are marked as
  loading at once, together with applying `f` to the state, so they appear
  together instead of one by one."
  [specs f]
  (let [loads (map vector specs (repeatedly #(swap! load-counter inc)))]
    (swap! app-state
      (fn [state]
        (reduce (fn [state [spec token]] (mark-loading state spec token))
          (f state)
          loads)))
    (doseq [[spec token] loads]
      (start-loader! spec token))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events

(defmulti dispatch! (fn [[evt]] evt))

(defmethod dispatch! :event/refresh
  [_evt]
  (let [specs (repo-specs @app-state)
        global-config (config/read-global)]
    (load-repos! specs
      (fn [state]
        (-> state
          (assoc :app-state/global-config global-config)
          (update :app-state/repos select-keys (map :repo/id specs)))))))

(defmethod dispatch! :event/refresh-repo
  [[_evt repo-id]]
  (when-let [repo (get-in @app-state [:app-state/repos repo-id])]
    (load-repos! [(select-keys repo [:repo/id :repo/name])] identity)))

(defn build-command-context
  "Builds the context map for command placeholder substitution.
   Includes SHAs, branches, and PR info for the selected change."
  [state]
  (when-let [{:keys [change base pr]} (selected-row state)]
    {:from-sha (or (:change/commit-sha base)
                   (:change/selected-branchname base))
     :to-sha (:change/commit-sha change)
     :from-branch (:change/selected-branchname base)
     :to-branch (:change/selected-branchname change)
     :pr-number (:pr/number pr)
     :pr-url (:pr/url pr)}))

(defn- run-in-fg!
  "Closes the TUI to run `f` in the foreground. Afterwards the TUI is reopened
  and `repo` is refreshed."
  [repo f]
  (swap! app-state assoc :app-state/run-in-fg
    {:fg/run f
     :fg/repo-id (:repo/id repo)})
  (tui/close!))

(defn run-command!
  "Executes a prepared command (with :strategy and :value) in the directory of
  `repo`, in the foreground."
  [repo cmd]
  (run-in-fg! repo
    #(custom-command/eval-command cmd {:dir (repo/project-dir repo)})))

(defmethod dispatch! :event/run-diff
  [_evt]
  (let [state @app-state]
    (when-let [context (build-command-context state)]
      (run-command! (selected-repo state)
        (config/diffview-command (global-config state) context)))))

(defmethod dispatch! :event/run-custom-command
  [[_evt key-str]]
  (let [state @app-state]
    (when-let [context (build-command-context state)]
      (when-let [cmd (config/custom-command (global-config state) key-str context)]
        (run-command! (selected-repo state) cmd)))))

(defmethod dispatch! :event/open-pr
  [_evt]
  (when-let [url (:pr/url (:pr (selected-row @app-state)))]
    (browse/browse-url url)))

(defmethod dispatch! :event/create-pr
  [_evt]
  (let [state @app-state
        {:keys [change base pr]} (selected-row state)
        repo (selected-repo state)]
    (when (and change (not (:pr/url pr)))
      (run-in-fg! repo
        (fn []
          (commands.create-prs/create-pr!
            {:head-change change
             :base-change base
             :prs (ui/fetch-prs-with-spinner (:repo/remote repo))
             :vcs (:repo/vcs repo)
             :remote (:repo/remote repo)
             :branch-infos (->> (vcs.branch/selected-branches-info
                                  (get-in repo [:repo/graph :load/result]))
                             (u/build-index :branch/branchname))}))))))

(defmethod dispatch! :event/merge-pr
  [_evt]
  (let [state @app-state
        {:keys [change base pr]} (selected-row state)
        repo (selected-repo state)]
    (when pr
      (run-in-fg! repo
        (fn []
          (when (tty/prompt-confirm
                  {:prompt
                   (format "Would you like to merge PR %s %s?\nThis will merge %s onto %s."
                     (ansi/colorize :blue (str "#" (:pr/number pr)))
                     (:pr/title pr)
                     (ansi/colorize :blue (:change/selected-branchname change))
                     (ansi/colorize :blue (:change/selected-branchname base)))})
            (remote/merge-pr! (:repo/remote repo) (:pr/number pr))
            ;; Clean up feature base if the merged branch was explicitly configured
            (let [merged-branch (:pr/head-branch pr)
                  project-dir (repo/project-dir repo)
                  local-config (config/read-local project-dir)
                  feature-bases (:feature-base-branches local-config)]
              (when (contains? feature-bases merged-branch)
                (let [updated-config (update local-config :feature-base-branches disj merged-branch)]
                  (config/write-local project-dir updated-config)
                  (println (ansi/colorize :green "✔")
                    "Removed" (ansi/colorize :cyan merged-branch)
                    "from feature base branches"))))
            (when (tty/prompt-confirm
                    {:prompt (str "Would you like to run " (ansi/colorize :yellow "prstack sync") "?")})
              ((:exec commands.sync/command) [] (repo-opts state repo)))))))))

(defmethod dispatch! :event/sync
  [_evt]
  (let [state @app-state]
    (when-let [repo (target-repo state)]
      (run-in-fg! repo
        #((:exec commands.sync/command) [] (repo-opts state repo))))))

(defn- select-tab! [f]
  (swap! app-state update :app-state/selected-tab f)
  (run! ensure-all-stacks! (keys (:app-state/repos @app-state))))

(defmethod dispatch! :event/select-tab
  [[_ tab-idx]]
  (select-tab! (constantly tab-idx)))

(defmethod dispatch! :event/tab-left
  [_]
  (select-tab! #(max 0 (dec %))))

(defmethod dispatch! :event/tab-right
  [_]
  (select-tab! #(min 2 (inc %))))

(defmethod dispatch! :event/move-up
  [_evt]
  (swap! app-state move-selection -1))

(defmethod dispatch! :event/move-down
  [_evt]
  (swap! app-state move-selection 1))

(comment
  (display-rows @app-state)
  (selected-row @app-state))
