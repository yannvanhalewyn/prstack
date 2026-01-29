(ns prstack.tui.app
  (:refer-clojure :exclude [run!])
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [bb-tty.tui :as tui]
    [clojure.string :as str]
    [prstack.init :as init]
    [prstack.tui.db :as db]
    [prstack.ui :as ui]
    [prstack.utils :as u]))

(defn- render-stack-section
  "Renders a single stack with PR information.
   Returns a sequence of lines to display."
  [state stack max-width]
  (concat
    ;; Render each branch in the stack (except the last one which is the base)
    (for [[cur-change prev-change]
          (u/consecutive-pairs
            (for [change stack]
              (let [is-selected? (= (:ui/idx change) (:app-state/selected-item-idx state))
                    formatted-change (ui/format-change change)]
                (assoc change
                  :ui/formatted formatted-change
                  :ui/visual-length (ansi/visual-length formatted-change)
                  :ui/is-selected? is-selected?))))]
      (let [[pr http-request wrong-base-branch] (db/sub-pr
                                                  (:change/selected-branchname cur-change)
                                                  (:change/selected-branchname prev-change))
            visual-len (:ui/visual-length cur-change)
            padding-needed (- max-width visual-len)
            padding (apply str (repeat padding-needed " "))
            padded-branchname (str (:ui/formatted cur-change) padding)
            display-branch (if(:ui/is-selected? cur-change)
                             (->> padded-branchname
                                  (ansi/strip-ansi)
                                  (ansi/colorize :bg-gray))
                             padded-branchname)]
        (str display-branch " "
             (ui/format-pr-info pr
               {:error (get-in http-request [:http/error :error/message])
                :pending? (= (:http/status http-request) :status/pending)
                :head-change cur-change
                :base-change prev-change
                :wrong-base-branch wrong-base-branch}))))
    ;; Render the base branch at the bottom
    [(ui/format-change (last stack))]))

(defn- handle-custom-command
  "Checks if a key has a custom command binding and runs it.
   Returns true if a custom command was found and executed."
  [key]
  (let [global-config (get-in @db/app-state [:app-state/system :system/global-config])
        keybinding (str (char key))]
    (when (get-in global-config [:commands keybinding])
      (db/dispatch! [:event/run-custom-command keybinding])
      true)))

(defn- render-stacks
  [_vcs]
  (tui/component
    {:on-key-press
     (fn [key]
       ;; First check for custom command bindings (allows overriding defaults)
       (when-not (handle-custom-command key)
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
           nil)))}
    (fn [state]
      (let [{:keys [regular-stacks feature-base-stacks]} (db/displayed-stacks state)
            all-stacks (concat regular-stacks feature-base-stacks)
            max-width
            (when (seq all-stacks)
              (when-let [counts
                         (seq
                           (mapcat
                             (fn [stack]
                               ;; Calculate visual width using JLine
                               (map (fn [change]
                                      (ansi/visual-length (ui/format-change change)))
                                 stack))
                             all-stacks))]
                (apply max counts)))]
        (cond->
          ;; When every stack has only the trunk node, that means no stacks
          (if (not (every? #(>= (count %) 1) regular-stacks))
            [(ansi/colorize :cyan "No stacks detetected")
             "Create some branches to see some stacks appear."]
            (when (and (seq regular-stacks)
                       (not (every? #(<= (count %) 1) regular-stacks)))
              (mapcat
                (fn [[i stack]]
                  (concat
                    [(ansi/colorize :cyan
                       ;; TODO better detect current stack in megamerges for example
                       (str "\uf51e "
                            (if (zero? i) "Current Stack" "Other Stack")
                            " (" (dec (count stack)) ")"))]
                    (render-stack-section state stack max-width)
                    ;; Add blank line between stacks
                    (when-not (and (= i (dec (count regular-stacks)))
                                   (empty? feature-base-stacks))
                      [""])))
                (u/indexed regular-stacks))))
          (seq feature-base-stacks)
          ;; Render feature base stacks
          (concat
            [(ansi/colorize :cyan "\uf126 Feature Base Branches")]
            (mapcat
              (fn [stack]
                (concat
                  (render-stack-section state stack max-width)
                  [""]))
              feature-base-stacks))
          #_
            [(ansi/colorize :cyan "No feature base branches found")])))))

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
    0 (render-stacks (db/vcs state))
    1 (render-stacks (db/vcs state))
    2 (render-all-stacks-tab)
    (render-stacks (db/vcs state))))

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
  (loop []
    (db/dispatch! [:event/refresh])
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
    (when-let [run-in-fg (:app-state/run-in-fg @db/app-state)]
      (swap! db/app-state dissoc :app-state/run-in-fg nil)
      (run-in-fg)
      (recur))))

(defn run! []
  (init/ensure-initialized!)
  (try
    (run-app!)
    (catch Exception e
      (println (ansi/colorize :red (str "Error: " (.getMessage e)))))))
