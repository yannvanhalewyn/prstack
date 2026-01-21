(ns prstack.tui.app
  (:refer-clojure :exclude [run!])
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [bb-tty.tui :as tui]
    [clojure.string :as str]
    [prstack.tui.db :as db]
    [prstack.ui :as ui]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn- render-stack-section
  "Renders a single stack with PR information.
   Returns a sequence of lines to display."
  [vcs state stack max-width]
  (concat
    ;; Render each branch in the stack (except the last one which is the base)
    (for [[cur-change prev-change]
          (u/consecutive-pairs
            (for [change stack]
              (let [is-selected? (= (:ui/idx change) (:app-state/selected-item-idx state))
                    ;; Always calculate uncolored for padding purposes
                    uncolored (ui/format-change vcs change {:no-color? true})
                    ;; Also get the colored version for non-selected items
                    colored (ui/format-change vcs change)]
                (assoc change
                  :ui/uncolored uncolored
                  :ui/colored colored
                  :ui/is-selected? is-selected?))))]
      (let [pr-info (db/sub-pr
                      (vcs/local-branchname vcs cur-change)
                      (vcs/local-branchname vcs prev-change))
            ;; Pad the uncolored version to get consistent visual width
            padded-uncolored (format (str "%-" max-width "s")
                               (:ui/uncolored cur-change))
            ;; Choose the display version based on selection
            display-branch (if (:ui/is-selected? cur-change)
                             ;; For selected: apply background to padded uncolored
                             (ansi/colorize :bg-gray padded-uncolored)
                             ;; For unselected: need to pad the colored version
                             ;; Calculate how much extra padding we need
                             (let [colored (:ui/colored cur-change)
                                   visual-len (count (:ui/uncolored cur-change))
                                   padding-needed (- max-width visual-len)
                                   padding (apply str (repeat padding-needed " "))]
                               (str colored padding)))]
        (str display-branch
             " "
             (cond
               (= (:http/status pr-info) :status/pending)
               (ansi/colorize :gray "Fetching...")

               (:pr/url pr-info)
               (str (case (:pr/status pr-info)
                      :pr.status/approved (ansi/colorize :green "✓")
                      :pr.status/changes-requested (ansi/colorize :red "✗")
                      :pr.status/review-required (ansi/colorize :yellow "●")
                      (ansi/colorize :gray "?"))
                    " "
                    (ansi/colorize :blue (str "#" (:pr/number pr-info)))
                    " " (:pr/title pr-info))

               (:missing pr-info)
               (str (ansi/colorize :red "X") " No PR Found")

               :else ""))))
    ;; Render the base branch at the bottom
    [(ui/format-change vcs (last stack))]))

(defn- render-stacks
  [vcs]
  (tui/component
    {:on-key-press
     (fn [key]
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
         nil))}
    (fn [state]
      (let [{:keys [regular-stacks feature-base-stacks]} (db/displayed-stacks state)
            all-stacks (concat regular-stacks feature-base-stacks)
            max-width
            (when (seq all-stacks)
              (when-let [counts
                         (seq
                           (mapcat
                             (fn [stack]
                               ;; Calculate visual width using uncolored text
                               (map (fn [change]
                                      (count (ui/format-change vcs change {:no-color? true})))
                                 stack))
                             all-stacks))]
                (apply max counts)))]
        (concat
          ;; Show "No stacks detected" when there are no regular stacks
          ;; or when all stacks only contain trunk (no feature branches)
          (when (or (not (seq regular-stacks))
                    (every? #(<= (count %) 1) regular-stacks))
            [(ansi/colorize :cyan "No stacks detetected")])

          ;; Render regular stacks (only if they have actual feature branches)
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
                  (render-stack-section vcs state stack max-width)
                  ;; Add blank line between stacks
                  (when-not (and (= i (dec (count regular-stacks)))
                                 (empty? feature-base-stacks))
                    [""])))
              (u/indexed regular-stacks)))

          ;; Render feature base stacks
          (when (seq feature-base-stacks)
            (concat
              [""]  ; Blank line before section
              [(ansi/colorize :cyan "\uf126 Feature Base Branches")]
              (mapcat
                (fn [stack]
                  (concat
                    (render-stack-section vcs state stack max-width)
                    [""]))
                feature-base-stacks))))))))

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
    0 (render-stacks (:app-state/vcs state))
    1 (render-stacks (:app-state/vcs state))
    2 (render-all-stacks-tab)
    (render-stacks (:app-state/vcs state))))

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

(defn run! []
  (loop []
    (db/dispatch! [:event/read-local-repo])
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
