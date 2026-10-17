(ns prstack.tui.app
  (:refer-clojure :exclude [run!])
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [bb-tty.tui :as tui]
    [clojure.string :as str]
    [prstack.config :as config]
    [prstack.init :as init]
    [prstack.tui.db :as db]
    [prstack.ui :as ui]))

(defn- branch-width
  "Returns the visual width of the branch column for a change or base row."
  [row]
  (ansi/visual-length (ui/format-change (:change row))))

(defn- render-change-row
  [{:keys [change base pr prs-load wrong-base-branch] :as row} selected? max-width]
  (let [formatted (ui/format-change change)
        padding (apply str (repeat (- max-width (branch-width row)) " "))
        padded-branchname (str formatted padding)
        display-branch (if selected?
                         (->> padded-branchname
                           (ansi/strip-ansi)
                           (ansi/colorize :bg-gray))
                         padded-branchname)]
    (str display-branch " "
         (ui/format-pr-info pr
           {:error (get-in prs-load [:load/error :error/message])
            :pending? (= (:load/status prs-load) :status/pending)
            :head-change change
            :base-change base
            :wrong-base-branch wrong-base-branch}))))

(defn- render-row
  "Renders a display row, see `db/display-rows`, as a line."
  [row selected-key max-width]
  (case (:row/kind row)
    :repo-header (ansi/colorize [:bold :blue] (str "▸ " (:repo/name row)))
    :stack-header (ansi/colorize :cyan
                    (str "\uf51e " (:label row) " (" (:size row) ")"))
    :section-header (ansi/colorize :cyan (:text row))
    :text (if-let [style (:style row)]
            (ansi/colorize style (:text row))
            (:text row))
    :blank ""
    :loading (ansi/colorize :gray "Loading...")
    ;; Same as errors in the PR column, see `ui/format-pr-info`
    :error (str (ansi/colorize :red "X") " " (:text row))
    :change (render-change-row row (= (:row/key row) selected-key) max-width)
    :base (ui/format-change (:change row))))

(defn- render-stacks []
  (tui/component
    {:on-key-press
     (fn [key]
       (let [key-str (str (char key))]
         ;; First check for custom command bindings (allows overriding defaults)
         (if (config/has-keybinding? (db/global-config @db/app-state) key-str)
           (db/dispatch! [:event/run-custom-command key-str])
           ;; Fall back to built-in commands
           (condp = key
             ;; Arrow keys are actually the following sequence
             ;; 27 91 68 (map char [27 91 68])
             ;; So need to keep a stack of recent keys to check for up/down
             (int \j) (db/dispatch! [:event/move-down])
             (int \k) (db/dispatch! [:event/move-up])
             (int \d) (db/dispatch! [:event/run-diff])
             (int \o) (db/dispatch! [:event/open-pr])
             (int \c) (db/dispatch! [:event/create-pr])
             (int \m) (db/dispatch! [:event/merge-pr])
             (int \s) (db/dispatch! [:event/sync])
             nil))))}
    (fn [state]
      (let [rows (db/display-rows state)
            selected-key (:row/key (db/selected-row state rows))
            max-width (->> rows
                        (filter #(#{:change :base} (:row/kind %)))
                        (map branch-width)
                        (apply max 0))]
        (map #(render-row % selected-key max-width) rows)))))

(defn- render-tabs
  [{:app-state/keys [selected-tab]}]
  (let [tabs ["Current Stacks" "My Stacks" "All Stacks"]
        render-tab (fn [idx tabname]
                     (if (= idx selected-tab)
                       (ansi/colorize :white tabname)
                       (ansi/colorize :gray tabname)))]
    [(str/join "  |  " (map-indexed render-tab tabs))
     ""]))

(defn- render-all-stacks-tab
  []
  (tui/component
    {}
    (fn [_state]
      ["All Stacks View"
       ""
       (ansi/colorize :cyan "This shows all stacks in the repository")
       (ansi/colorize :gray "Feature coming soon...")])))

(defn- render-tab-content
  [state]
  (case (:app-state/selected-tab state)
    0 (render-stacks)
    1 (render-stacks)
    2 (render-all-stacks-tab)
    (render-stacks)))

(defn- render-keybindings []
  (let [{:keys [cols]} (tty/get-terminal-size)
        separator (str/join (repeat (or cols 80) "\u2500"))
        keybindings
        [{:keybind/display "0-9"
          :keybind/name "Switch tabs"}
         {:keybind/display "j/k"
          :keybind/name "Navigate"}
         {:keybind/display "d"
          :keybind/name "Diff"}
         {:keybind/display "o"
          :keybind/name "Open PR"}
         {:keybind/display "c"
          :keybind/name "Create PR"}
         {:keybind/display "m"
          :keybind/name "Merge PR"}
         {:keybind/display "s"
          :keybind/name "Sync"}
         {:keybind/display "r"
          :keybind/name "Refresh"}
         {:keybind/display "q"
          :keybind/name "Quit"}]]
    [""
     (ansi/colorize :gray separator)
     (ansi/colorize :gray (str/join "  "
                            (for [kb keybindings]
                              (format "%s: %s" (:keybind/display kb) (:keybind/name kb)))))]))

(defn run-app! []
  (db/dispatch! [:event/refresh])
  (loop []
    (tui/run-ui!
      (tui/mount! db/app-state
        (tui/component
          {:on-key-press
           (fn [key]
             (cond
               (= key (int \q)) (tui/close!)
               (= key (int \r)) (db/dispatch! [:event/refresh])
               (= key (int \1)) (db/dispatch! [:event/select-tab 0])
               (= key (int \2)) (db/dispatch! [:event/select-tab 1])
               (= key (int \3)) (db/dispatch! [:event/select-tab 2])
               (= key (int \h)) (db/dispatch! [:event/tab-left])
               (= key (int \l)) (db/dispatch! [:event/tab-right])))}
          (fn [state]
            (tui/block
              [(render-tabs state)
               (render-tab-content state)
               (render-keybindings)])))))
    (when-let [{:fg/keys [run repo-id]} (:app-state/run-in-fg @db/app-state)]
      (swap! db/app-state dissoc :app-state/run-in-fg)
      (run)
      ;; The command could have changed the repo
      (db/dispatch! [:event/refresh-repo repo-id])
      (recur))))

(defn run!
  "Runs the TUI on the repository at `:project-dir`, or on all repositories in
  `:workspace-dir`."
  [global-opts]
  ;; Repositories in a workspace use an inferred config when they have none
  (when-not (:workspace-dir global-opts)
    (init/ensure-initialized! (:project-dir global-opts)))
  (swap! db/app-state assoc :app-state/global-opts global-opts)
  (try
    (run-app!)
    (catch Exception e
      (println (ansi/colorize :red (str "Error: " (.getMessage e)))))))
