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
    [prstack.remote :as remote]
    [prstack.repo :as repo]
    [prstack.repo.loader :as loader]
    [prstack.tools.clipboard :as tools.clipboard]
    [prstack.tools.schema :as tools.schema]
    [prstack.tui.rows :as rows]
    [prstack.ui :as ui]
    [prstack.utils :as u]
    [prstack.workspace :as workspace]))

(def ^:lsp/allow-unused RepoState
  "A repo as held in the app state: the repo and the data loaded through it.
  The repo's clients are only present once it's opened, see `loader/load!`."
  (tools.schema/merge
    (tools.schema/merge repo/Repo loader/RepoLoad)
    [:map
     ;; Results of earlier loads are discarded, see `update-loading-repo`
     [:repo/load-token :int]]))

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
   ;; Nil, or no longer displayed, selects a fallback row. See `rows/selected-row`.
   [:app-state/selection {:optional true} RowKey]
   ;; Short feedback shown in the footer, cleared after a few seconds
   [:app-state/message {:optional true} :string]
   ;; After pressing `y`, the next key picks what to copy
   [:app-state/copy-menu-open? {:optional true} :boolean]
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

(defn- repo-opts
  "Returns the global opts for running a CLI command on `repo`."
  [state repo]
  (assoc (global-opts state) :project-dir (repo/project-dir repo)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Selection

(defn- selected-repo [state]
  (some->> (rows/selected-row state) :repo/id (get (:app-state/repos state))))

(defn- target-repo
  "Returns the repo to act on for actions that don't need a selected change,
  like sync: the repo of the selected row, or else the first opened repo."
  [state]
  (or (selected-repo state)
      (u/find-first :repo/vcs (rows/repos state))))

(defn- move-selection
  "Moves the selection `offset` rows down, staying within the rows."
  [state offset]
  (let [selectable (rows/selectable-rows (rows/display-rows state))]
    (if-let [current (rows/resolve-selection selectable (:app-state/selection state))]
      (let [idx (u/find-index #(= (:row/key %) (:row/key current)) selectable)
            new-idx (-> (+ idx offset) (max 0) (min (dec (count selectable))))]
        (assoc state :app-state/selection (:row/key (nth selectable new-idx))))
      state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Loading
;;
;; Every repo is loaded on its own thread, see `loader/load!`. Every load gets a
;; token, and loaders only write to their repo while that token is current, so
;; results of a load that was superseded by a refresh are dropped.

(defonce ^:private load-counter (atom 0))

(defn- update-loading-repo
  "Updates the repo with `repo-id` if `token` is still its current load token."
  [state repo-id token f & args]
  (if (= token (get-in state [:app-state/repos repo-id :repo/load-token]))
    (apply update-in state [:app-state/repos repo-id] f args)
    state))

(defn- repo-updater
  "Returns the `update!` function for loading the repo with `repo-id`, see
  `loader/load!`, which only writes while `token` is current."
  [repo-id token]
  (fn [f & args]
    (apply swap! app-state update-loading-repo repo-id token f args)))

(defn- ensure-all-stacks!
  "Starts loading all stacks of the repo with `repo-id` when the selected tab
  displays them, its graph is loaded and they are not loaded or loading yet."
  [repo-id]
  (let [[old-state new-state]
        (swap-vals! app-state
          (fn [state]
            (if (and (rows/shows-all-stacks? state)
                     (loader/needs-all-stacks? (get-in state [:app-state/repos repo-id])))
              (update-in state [:app-state/repos repo-id] loader/mark-loading-all-stacks)
              state)))]
    (when-not (identical? old-state new-state)
      (let [repo (get-in new-state [:app-state/repos repo-id])]
        (loader/load-all-stacks! repo
          (repo-updater repo-id (:repo/load-token repo)))))))

(defn- mark-loading
  "Marks the repo of `spec` as loading with `token`, see `loader/mark-loading`."
  [state {:repo/keys [id] :as spec} token]
  (update-in state [:app-state/repos id]
    #(-> (merge % spec)
       (loader/mark-loading)
       (assoc :repo/load-token token))))

(defn- load-repos!
  "Loads the repos of `specs`, see `loader/load!`. All repos are marked as
  loading at once, together with applying `f` to the state, so they appear
  together instead of one by one."
  [specs f]
  (let [loads (map vector specs (repeatedly #(swap! load-counter inc)))]
    (swap! app-state
      (fn [state]
        (reduce (fn [state [spec token]] (mark-loading state spec token))
          (f state)
          loads)))
    (doseq [[{:repo/keys [id]} token] loads]
      (loader/load! id
        {:update! (repo-updater id token)
         :on-graph-loaded #(ensure-all-stacks! id)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events

(defmulti dispatch! (fn [[evt]] evt))

(defmethod dispatch! :event/refresh
  [_evt]
  (let [specs (workspace/repo-infos (global-opts @app-state))
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

(defn- command-context
  "Returns the context for running a command on the selected change, see
  `custom-command/context`."
  [state]
  (some-> (rows/selected-row state) custom-command/context))

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
    (when-let [context (command-context state)]
      (run-command! (selected-repo state)
        (config/diffview-command (global-config state) context)))))

(defmethod dispatch! :event/run-custom-command
  [[_evt key-str]]
  (let [state @app-state]
    (when-let [context (command-context state)]
      (when-let [cmd (config/custom-command (global-config state) key-str context)]
        (run-command! (selected-repo state) cmd)))))

(defmethod dispatch! :event/open-pr
  [_evt]
  (when-let [url (:pr/url (:pr (rows/selected-row @app-state)))]
    (browse/browse-url url)))

(def ^:private message-duration-ms 2000)

(defn- show-message!
  "Shows `message` in the footer for a few seconds."
  [message]
  (swap! app-state assoc :app-state/message message)
  (future
    (Thread/sleep message-duration-ms)
    ;; `identical?` so that an equal, newer message isn't cleared early
    (swap! app-state
      #(cond-> % (identical? message (:app-state/message %))
         (dissoc :app-state/message)))))

(defn- copy! [text message]
  (tools.clipboard/copy! text)
  (show-message! message))

(defn- stack-markdown
  "Returns the PRs of the stack of `row` as a markdown list of links, from leaf
  to base like they're displayed. Branches without a PR are left out."
  [state row]
  (let [{repo-id :repo/id stack-id :stack/id} (:row/key row)]
    (->> (rows/display-rows state)
      (filter #(and (= :change (:row/kind %))
                    (= repo-id (get-in % [:row/key :repo/id]))
                    (= stack-id (get-in % [:row/key :stack/id]))))
      (keep :pr)
      (map #(format "- [#%s %s](%s)" (:pr/number %) (:pr/title %) (:pr/url %)))
      (str/join "\n")
      not-empty)))

(defmethod dispatch! :event/open-copy-menu
  [_evt]
  (when (rows/selected-row @app-state)
    (swap! app-state #(-> %
                        (assoc :app-state/copy-menu-open? true)
                        (dissoc :app-state/message)))))

(defmethod dispatch! :event/copy-menu-key
  [[_evt key]]
  (swap! app-state dissoc :app-state/copy-menu-open?)
  (let [state @app-state]
    (when-let [{:keys [change pr] :as row} (rows/selected-row state)]
      (case key
        "y" (if-let [url (:pr/url pr)]
              (copy! url (str "Copied " url))
              (show-message! "No PR for this branch"))
        "t" (if-let [title (:pr/title pr)]
              (copy! title (str "Copied " title))
              (show-message! "No PR for this branch"))
        "b" (let [branch (:change/selected-branchname change)]
              (copy! branch (str "Copied " branch)))
        "s" (if-let [markdown (stack-markdown state row)]
              (copy! markdown
                (format "Copied %s PRs of the stack as markdown"
                  (count (str/split-lines markdown))))
              (show-message! "No PRs in this stack"))
        ;; Any other key cancels
        nil))))

(defmethod dispatch! :event/create-pr
  [_evt]
  (let [state @app-state
        {:keys [change base pr]} (rows/selected-row state)
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
             :branch-infos (commands.create-prs/branch-infos
                             (get-in repo [:repo/graph :load/result]))}))))))

(defmethod dispatch! :event/merge-pr
  [_evt]
  (let [state @app-state
        {:keys [change base pr]} (rows/selected-row state)
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
            (doseq [branch (config/remove-feature-base-branches! (repo/project-dir repo)
                             [(:pr/head-branch pr)])]
              (println (ansi/colorize :green "✔")
                "Removed" (ansi/colorize :cyan branch)
                "from feature base branches"))
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
  (rows/display-rows @app-state)
  (rows/selected-row @app-state))
