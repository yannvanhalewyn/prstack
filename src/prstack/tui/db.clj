(ns prstack.tui.db
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [bb-tty.tui :as tui]
    [clojure.java.browse :as browse]
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
    [prstack.vcs.branch :as vcs.branch]))

(defn- http-request-schema [result-schema]
  [:map
   [:htt/status [:enum :status/pending :status/failed :status/success]]
   [:http/result result-schema]
   [:http/error
    [:map [:error/type :keyword
           [:error/message :string]]]]])

(def ^:lsp/allow-unused RepoState
  "A repo as held in the app state: the repo and the data read through it."
  (tools.schema/merge
    repo/Repo
    [:map
     [:repo/vcs-graph [:fn delay?]]
     [:repo/current-stacks [:fn delay?]]
     [:repo/all-stacks [:fn delay?]]
     [:repo/prs (http-request-schema [:sequential remote/PR])]]))

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
   [:app-state/run-in-fg :fn]])

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

(defn- repo-dirs
  "Returns the directories of the repositories shown in the TUI."
  [state]
  [(:project-dir (global-opts state))])

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

(defn- tab-stacks
  "Returns the stacks of `repo` displayed on the selected tab, split into
  `:regular-stacks` and `:feature-base-stacks`.

   For the 'My Stacks' tab, stacks are sorted with non-feature-base stacks
   first, then feature-base stacks alphabetically."
  [state repo]
  (let [tab (:app-state/selected-tab state)
        stacks (if (= 0 tab)
                 @(:repo/current-stacks repo)
                 @(:repo/all-stacks repo))]
    (stack/split-feature-base-stacks
      (if (= 1 tab)
        (sort-stacks-by-base stacks)
        stacks))))

(defn- stack-rows
  "Returns a row for every change in `stack` from leaf to base, where the base
  change is not selectable."
  [repo stack]
  (let [repo-id (:repo/id repo)
        stack-id (stack/id stack)
        prs-request (:repo/prs repo)
        prs (:http/result prs-request)
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
           :prs-request prs-request
           ;; Set when the PR has the wrong base branch
           :wrong-base-branch (when (and pr (not= (:pr/base-branch pr) base-branch))
                                base-branch)}))
      [{:row/kind :base
        :repo/id repo-id
        :change (last changes)}])))

(defn- repo-rows [state repo]
  (let [{:keys [regular-stacks feature-base-stacks]} (tab-stacks state repo)]
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

(defn display-rows
  "Returns the rows displayed for all repos. Repo headers are only shown when
  there are multiple repos."
  [state]
  (let [repos (repos state)
        multiple-repos? (> (count repos) 1)]
    (into []
      (mapcat
        (fn [repo]
          (concat
            (when multiple-repos?
              [{:row/kind :repo-header
                :repo/id (:repo/id repo)
                :repo/name (:repo/name repo)}])
            (repo-rows state repo))))
      repos)))

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
  like sync: the repo of the selected row, or else the first repo."
  [state]
  (or (selected-repo state)
      (first (repos state))))

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
;; Events

(defmulti dispatch! (fn [[evt]] evt))

(defn- open-repo-state
  "Opens the repository at `project-dir` as a repo, which reads its graph and
  stacks lazily."
  [project-dir]
  (let [repo (repo/open project-dir (config/read-local project-dir))
        vcs-graph (delay (vcs/read-graph (:repo/vcs repo)
                           (:repo/local-config repo)))]
    (assoc repo
      :repo/vcs-graph vcs-graph
      :repo/current-stacks (delay (stack/get-current-stacks repo @vcs-graph))
      :repo/all-stacks (delay (stack/get-all-stacks repo @vcs-graph)))))

(defn- update-repo
  "Updates the repo with `repo-id`, ignoring repos that are no longer in the
  state."
  [state repo-id f & args]
  (if (get-in state [:app-state/repos repo-id])
    (apply update-in state [:app-state/repos repo-id] f args)
    state))

(defmethod dispatch! :event/read-repos
  [_evt]
  (let [repos (u/build-index :repo/id (map open-repo-state (repo-dirs @app-state)))]
    (swap! app-state
      (fn [state]
        (assoc state
          :app-state/global-config (config/read-global)
          ;; Keep the PRs until they are fetched again
          :app-state/repos
          (into {}
            (map (fn [[id repo]]
                   [id (assoc repo :repo/prs
                         (get-in state [:app-state/repos id :repo/prs]))]))
            repos))))))

(defmethod dispatch! :event/fetch-prs
  [_evt]
  (doseq [{:repo/keys [id remote]} (repos @app-state)]
    (swap! app-state update-repo id assoc :repo/prs
      {:http/status :status/pending})
    (future
      (let [[result err] (remote/list-prs remote {})]
        (swap! app-state update-repo id assoc :repo/prs
          (if err
            {:http/status :status/failed
             :http/error err}
            {:http/status :status/success
             :http/result result}))))))

(defmethod dispatch! :event/refresh
  [_evt]
  (dispatch! [:event/read-repos])
  (dispatch! [:event/fetch-prs]))

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

(defn run-command!
  "Executes a prepared command (with :strategy and :value) in the directory of
  `repo`. Sets up the app state to run in foreground and closes the TUI."
  [repo cmd]
  (swap! app-state assoc :app-state/run-in-fg
    #(custom-command/eval-command cmd {:dir (repo/project-dir repo)}))
  (tui/close!))

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
      (swap! app-state assoc :app-state/run-in-fg
        (fn []
          (commands.create-prs/create-pr!
            {:head-change change
             :base-change base
             :prs (ui/fetch-prs-with-spinner (:repo/remote repo))
             :vcs (:repo/vcs repo)
             :remote (:repo/remote repo)
             :branch-infos (->> (vcs.branch/selected-branches-info @(:repo/vcs-graph repo))
                             (u/build-index :branch/branchname))})))
      (tui/close!))))

(defmethod dispatch! :event/merge-pr
  [_evt]
  (let [state @app-state
        {:keys [change base pr]} (selected-row state)
        repo (selected-repo state)]
    (when pr
      (swap! app-state assoc :app-state/run-in-fg
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
              ((:exec commands.sync/command) [] (repo-opts state repo))))))
      (tui/close!))))

(defmethod dispatch! :event/sync
  [_evt]
  (let [state @app-state]
    (when-let [repo (target-repo state)]
      (swap! app-state assoc :app-state/run-in-fg
        #((:exec commands.sync/command) [] (repo-opts state repo)))
      (tui/close!))))

(defmethod dispatch! :event/select-tab
  [[_ tab-idx]]
  (swap! app-state assoc :app-state/selected-tab tab-idx))

(defmethod dispatch! :event/tab-left
  [_]
  (swap! app-state update :app-state/selected-tab #(max 0 (dec %))))

(defmethod dispatch! :event/tab-right
  [_]
  (swap! app-state update :app-state/selected-tab #(min 2 (inc %))))

(defmethod dispatch! :event/move-up
  [_evt]
  (swap! app-state move-selection -1))

(defmethod dispatch! :event/move-down
  [_evt]
  (swap! app-state move-selection 1))

(comment
  (display-rows @app-state)
  (selected-row @app-state))
