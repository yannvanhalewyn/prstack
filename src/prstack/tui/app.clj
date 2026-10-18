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
    [prstack.tui.rows :as rows]
    [prstack.ui :as ui]
    [prstack.utils :as u]))

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
  "Renders a display row, see `rows/display-rows`, as a line."
  [row selected-key max-width]
  (case (:row/kind row)
    :repo-header (ui/format-repo-header (:repo/name row))
    :stack-header (ansi/colorize :cyan
                    (str "\uf51e " (:label row) " (" (:size row) ")"))
    :section-header (ansi/colorize :cyan (:text row))
    :text (if-let [style (:style row)]
            (ansi/colorize style (:text row))
            (:text row))
    :blank ""
    :loading (ansi/colorize :gray
               (if-let [repo-names (seq (:repo-names row))]
                 (str "Loading " (str/join ", " repo-names) "...")
                 "Loading..."))
    ;; Same as errors in the PR column, see `ui/format-pr-info`
    :error (str (ansi/colorize :red "X") " " (:text row))
    :change (render-change-row row (= (:row/key row) selected-key) max-width)
    :base (ui/format-change (:change row))))

(defn- render-stacks [height]
  (tui/component
    {:on-key-press
     (fn [key]
       ;; First check for custom command bindings (allows overriding defaults)
       (if (and (string? key)
                (config/has-keybinding? (db/global-config @db/app-state) key))
         (db/dispatch! [:event/run-custom-command key])
         ;; Fall back to built-in commands
         (case key
           ("j" :down) (db/dispatch! [:event/move-down])
           ("k" :up) (db/dispatch! [:event/move-up])
           "d" (db/dispatch! [:event/run-diff])
           "o" (db/dispatch! [:event/open-pr])
           "y" (db/dispatch! [:event/copy-pr-url])
           "c" (db/dispatch! [:event/create-pr])
           "m" (db/dispatch! [:event/merge-pr])
           "s" (db/dispatch! [:event/sync])
           nil)))}
    (fn [state]
      (let [rows (rows/display-rows state)
            selected-key (:row/key (rows/selected-row state rows))
            max-width (->> rows
                        (filter #(#{:change :base} (:row/kind %)))
                        (map branch-width)
                        (apply max 0))]
        (tui/scroll-view
          {:id ::stacks
           :height height
           :focus (when selected-key
                    (u/find-index #(= (:row/key %) selected-key) rows))}
          (map #(render-row % selected-key max-width) rows))))))

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
  [height]
  (tui/scroll-view
    {:id ::all-stacks
     :height height}
    ["All Stacks View"
     ""
     (ansi/colorize :cyan "This shows all stacks in the repository")
     (ansi/colorize :gray "Feature coming soon...")]))

(defn- render-tab-content
  "Renders the content of the selected tab in `height` lines."
  [state height]
  (case (:app-state/selected-tab state)
    0 (render-stacks height)
    1 (render-stacks height)
    2 (render-all-stacks-tab height)
    (render-stacks height)))

(defn- render-footer [state]
  (let [{:keys [cols]} (tty/get-terminal-size)
        separator (str/join (repeat (or cols 80) "\u2500"))
        keybindings
        [{:keybind/display "0-9/←/→"
          :keybind/name "Switch tabs"}
         {:keybind/display "j/k/↑/↓"
          :keybind/name "Navigate"}
         {:keybind/display "d"
          :keybind/name "Diff"}
         {:keybind/display "o"
          :keybind/name "Open PR"}
         {:keybind/display "y"
          :keybind/name "Copy PR URL"}
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
     (if-let [message (:app-state/message state)]
       (ansi/colorize :green message)
       (ansi/colorize :gray (str/join "  "
                              (for [kb keybindings]
                                (format "%s: %s" (:keybind/display kb) (:keybind/name kb))))))]))

(defn run-app! []
  (db/dispatch! [:event/refresh])
  (loop []
    (tui/run-ui!
      (tui/mount! db/app-state
        (tui/component
          {:on-key-press
           (fn [key]
             (case key
               "q" (tui/close!)
               "r" (db/dispatch! [:event/refresh])
               "1" (db/dispatch! [:event/select-tab 0])
               "2" (db/dispatch! [:event/select-tab 1])
               "3" (db/dispatch! [:event/select-tab 2])
               ("h" :left) (db/dispatch! [:event/tab-left])
               ("l" :right) (db/dispatch! [:event/tab-right])
               nil))}
          (fn [state]
            (let [header (render-tabs state)
                  footer (render-footer state)
                  ;; The block adds a blank line above and below
                  height (some-> (tty/get-terminal-size) :rows
                           (- 2 (count header) (count footer)))]
              (tui/block
                [header
                 (render-tab-content state height)
                 footer]))))))
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
