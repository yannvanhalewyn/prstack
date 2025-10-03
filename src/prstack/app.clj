(ns prstack.app
  (:refer-clojure :exclude [run!])
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [bb-tty.tui :as tui]
    [clojure.string :as str]
    [prstack.app.db :as app.db]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn- format-change
  "Formats the bookmark as part of a stack at the given index"
  ([change]
   (format-change change {}))
  ([change {:keys [trunk?]}]
   (str
     (if trunk?
       " \ue729 " #_"└─" #_" \ueb06 "  #_" \ueafc "
       " \ue0a0 ")
     (vcs/local-branchname change))))

(defn- render-stacks
  []
  (tui/component
    {:on-key-press
     (fn [key]
       (condp = key
         ;; Arrow keys are actually the following sequence
         ;; 27 91 68 (map char [27 91 68])
         ;; So need to keep a stack of recent keys to check for up/down
         (int \j) (app.db/dispatch! [:event/move-down])
         (int \k) (app.db/dispatch! [:event/move-up])
         (int \d) (app.db/dispatch! [:event/run-diff])
         (int \o) (app.db/dispatch! [:event/open-pr])
         (int \c) (app.db/dispatch! [:event/create-pr])
         (int \m) (app.db/dispatch! [:event/merge-pr])
         (int \s) (app.db/dispatch! [:event/sync])
         nil))}
    (fn [state]
      (if-let [stacks (seq (app.db/displayed-stacks state))]
        (let [max-width
              (when-let [counts
                         (seq
                           (mapcat
                             (fn [stack]
                               (map (comp count format-change) stack))
                             stacks))]
                (apply max counts))]
          (for [[i stack] (u/indexed stacks)]
            (concat
              [(ansi/colorize :cyan
                 ;; TODO better detect current stack in megamerges for example
                 (str "\uf51e " (if (zero? i) "Current Stack" "Other Stack")))]
              (for [[cur-change prev-change]
                    (u/consecutive-pairs
                      (for [change stack]
                        (assoc change
                          :ui/formatted-change
                          (format-change change))))]
                (let [pr-info (app.db/sub-pr
                                (vcs/local-branchname cur-change)
                                (vcs/local-branchname prev-change))
                      padded-bookmark (format (str "%-" max-width "s")
                                        (:ui/formatted-change cur-change))]
                  (str (if (= (:ui/idx cur-change) (:app-state/selected-item-idx state))
                         (ansi/colorize :bg-gray padded-bookmark)
                         padded-bookmark)
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
                         ;; TODO Show if 'needs push'
                         (:missing pr-info)
                         (str (ansi/colorize :red "X") " No PR Found")

                         :else ""))))
              [(format-change (last stack) {:trunk? true})]
              (when-not (= i (dec (count stacks)))
                [""]))))
        (ansi/colorize :cyan "No stacks detetected")))))

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

(defn run! []
  (app.db/dispatch! [:event/read-local-repo])
  (loop []
    (tui/run-ui!
      (tui/mount! app.db/app-state
        (tui/component
          {:on-key-press
           (fn [key]
             (cond
               (= key (int \q)) (tui/close!)
               (= key (int \r)) (app.db/dispatch! [:event/refresh])
               (= key (int \1)) (app.db/dispatch! [:event/select-tab 0])
               (= key (int \2)) (app.db/dispatch! [:event/select-tab 1])
               (= key (int \3)) (app.db/dispatch! [:event/select-tab 2])))}
          (fn [state]
            (tui/block
              [(render-tabs state)
               (render-tab-content state)
               (render-keybindings)])))))
    (when-let [run-in-fg (:app-state/run-in-fg @app.db/app-state)]
      (swap! app.db/app-state dissoc :app-state/run-in-fg nil)
      (run-in-fg)
      (recur))))
