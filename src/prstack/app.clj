(ns prstack.app
  (:refer-clojure :exclude [run!])
  (:require
    [clojure.java.browse :as browse]
    [clojure.string :as str]
    [prstack.commands.sync :as commands.sync]
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.tty :as tty]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defonce app-state
  (atom {:app-state/selected-item-idx 0
         :app-state/prs {}
         :app-state/selected-tab 0}))

(defn- format-change
  "Formats the bookmark as part of a stack at the given index"
  [{:keys [vcs-config change]}]
  (let [branchname (vcs/local-branchname change) ]
    (str
      (if (= branchname (:vcs-config/trunk-bookmark vcs-config))
        " \ue729 " #_"└─" #_" \ueb06 "  #_" \ueafc "
        " \ue0a0 ")
      (vcs/local-branchname change))))

(defn- assoc-ui-indices
  "Takes a list of stacks, and assoc's a `:ui/idx` key to every change in each
  stack in order."
  [stacks]
  (second
   (reduce
     (fn [[idx ret] stack]
       (let [[idx stack]
             (reduce (fn [[idx ret] change]
                       [(inc idx) (conj ret (assoc change :ui/idx idx))])
               [idx []]
               stack)]
         [idx (conj ret stack)]))
     [0 []]
     stacks)))

(comment
  (assoc-ui-indices
    [[{:change/local-bookmarks ["main"]} {:change/local-bookmarks ["test"]}]
     [{:change/local-bookmarks ["main"]} {:change/local-bookmarks ["test2"]}]]))

(defn displayed-stacks [state]
  (assoc-ui-indices
    (stack/reverse-stacks
      (case (:app-state/selected-tab state)
        0 @(:app-state/current-stacks state)
        1 @(:app-state/all-stacks state)
        @(:app-state/all-stacks state)))))

(defmulti dispatch! (fn [[evt]] evt))

(defmethod dispatch! :event/fetch-pr
  [[_ head-branch base-branch]]
  (when-not (get-in @app-state [:app-state/prs head-branch base-branch])
    (future
      (swap! app-state update :app-state/prs assoc-in [head-branch base-branch]
        {:pr/url
         (vcs/find-pr head-branch base-branch)}))))

(defmethod dispatch! :event/run-diff
  [_evt]
  (let [state @app-state
        leaves (stack/leaves (displayed-stacks state))
        idx (:app-state/selected-item-idx state)]
   (when (< idx (dec (count leaves)))
     (let [selected-change (nth leaves idx)
           prev-change (nth leaves (inc idx))
           from-ref
           (or
             (:change/commit-sha prev-change)
             (vcs/local-branchname prev-change))
           to-ref (:change/commit-sha selected-change)]
       (swap! app-state assoc ::run-in-fg
         #(u/shell-out ["/Users/yannvanhalewyn/repos/nvim-macos-x86_64/bin/nvim" "-c"
                        (str "DiffviewOpen " from-ref "..." to-ref)]))
       (tty/close!)))))

(defmethod dispatch! :event/open-pr
  [_evt]
  (let [state @app-state
        leaves (stack/leaves (displayed-stacks state))
        head-branch (vcs/local-branchname
                      (nth leaves (:app-state/selected-item-idx state)))
        base-branch (vcs/local-branchname
                      (nth leaves (inc (:app-state/selected-item-idx state))))]
    (println {:head-branch head-branch :base-branch base-branch})
    (when-let [url (get-in state [:app-state/prs head-branch base-branch :pr/url])]
      (browse/browse-url url))))

(defmethod dispatch! :event/sync
  [_evt]
  (swap! app-state assoc ::run-in-fg #((:exec commands.sync/command) []))
  (tty/close!))

(defmethod dispatch! :event/select-tab
  [[_ tab-idx]]
  (swap! app-state assoc :app-state/selected-tab tab-idx))

(defmethod dispatch! :event/move-up
  [_evt]
  (swap! app-state update :app-state/selected-item-idx
    #(max 0 (dec %))))

(defmethod dispatch! :event/move-down
  [_evt]
  (swap! app-state update :app-state/selected-item-idx
    #(min (dec (count (stack/leaves (displayed-stacks @app-state))))
       (inc %))))

(defn- render-stacks
  [{:keys [vcs-config prs]}]
  (tty/component
    {:on-key-press
     (fn [key]
       (condp = key
         ;; Arrow keys are actually the following sequence
         ;; 27 91 68 (map char [27 91 68])
         ;; So need to keep a stack of recent keys to check for up/down
         (int \j) (dispatch! [:event/move-down])
         (int \k) (dispatch! [:event/move-up])
         (int \d) (dispatch! [:event/run-diff])
         (int \o) (dispatch! [:event/open-pr])
         (int \s) (dispatch! [:event/sync])
         nil))}
    (fn [state]
      (if-let [stacks (seq (displayed-stacks state))]
        (let [max-width
              (when-let [counts
                         (seq
                           (mapcat (fn [stack]
                                     (->> stack
                                       (map #(format-change
                                               {:change %
                                                :vcs-config (:app-state/vcs-config state)}))
                                       (map count)))
                             stacks))]
                (apply max counts))]
          [(tty/colorize :cyan (str "\uf51e " "Stack"))
           (for [stack stacks
                 [change formatted-bookmark]
                 (->> stack
                   (map #(format-change {:change % :vcs-config vcs-config}))
                   (map vector stack))]
             (let [head-branch (vcs/local-branchname change)
                   base-branch (vcs/local-branchname (get stack (inc (:ui/idx change))))
                   pr-info (when base-branch
                             (dispatch! [:event/fetch-pr head-branch base-branch])
                             (or (get-in prs [head-branch base-branch])
                                 {:http/status :pending}))
                   padded-bookmark (format (str "%-" max-width "s") formatted-bookmark)]
               (str (if (= (:ui/idx change) (:app-state/selected-item-idx state))
                      (tty/colorize :bg-gray padded-bookmark)
                      padded-bookmark)
                    " "
                    (cond
                      (= (:http/status pr-info) :pending)
                      (tty/colorize :gray "Fetching...")

                      (:pr/url pr-info)
                      (str (tty/colorize :green "✔") " PR Found"
                           (tty/colorize :gray (str " (" (:pr/url pr-info) ")")))
                      ;; TODO Show if 'needs push'
                      (contains? pr-info :pr/url)
                      (str (tty/colorize :red "X") " No PR Found")

                      :else ""))))])
        (tty/colorize :cyan "No stacks detetected")))))

(defn- render-tabs
  [{:app-state/keys [selected-tab]}]
  (let [tabs ["Current Stacks" "My Stacks" "All Stacks"]
        render-tab (fn [idx tabname]
                     (if (= idx selected-tab)
                       (tty/colorize :white tabname)
                       (tty/colorize :gray tabname)))]
    [(str/join "  |  " (map-indexed render-tab tabs))
     ""]))

(defn- render-current-stacks-tab
  [state]
  (render-stacks
    {:stacks @(:app-state/current-stacks state)
     :prs (:app-state/prs state)
     :vcs-config (:app-state/vcs-config state)}))

(defn- render-my-stacks-tab
  [{:app-state/keys [all-stacks vcs-config prs]}]
  (render-stacks
    {:stacks @all-stacks
     :prs prs
     :vcs-config vcs-config}))

(defn- render-all-stacks-tab
  [{:app-state/keys [config vcs-config]}]
  (tty/component
    {}
    (fn [_]
      ["All Stacks View"
       ""
       (tty/colorize :cyan "This shows all stacks in the repository")
       (tty/colorize :gray "Feature coming soon...")])))

(defn- render-tab-content
  [state]
  (case (:app-state/selected-tab state)
    0 (render-current-stacks-tab state)
    1 (render-my-stacks-tab state)
    2 (render-all-stacks-tab state)
    (render-current-stacks-tab state)))

(defn- render-keybindings []
  (let [{:keys [cols]} (tty/get-terminal-size)
        separator (str/join (repeat (or cols 80) "\u2500"))
        keybindings ["[0-9]: Switch tabs" "j/k: Navigate" "d: Diff" "o: Open PR" "s: Sync" "q: Quit"]]
    [""
     (tty/colorize :gray separator)
     (tty/colorize :gray (str/join "  " keybindings))]))

(defn run! []
  (let [config (config/read-local)
        vcs-config (vcs/config)]
    (swap! app-state merge
      {:app-state/config config
       :app-state/vcs-config vcs-config
       :app-state/current-stacks (delay (stack/get-current-stacks vcs-config))
       :app-state/all-stacks (delay (stack/get-all-stacks vcs-config config))})
    (loop []
      (tty/run-ui!
        (tty/render! app-state
          (tty/component
            {:on-key-press
             (fn [key]
               (cond
                 (= key (int \q)) (tty/close!)
                 (= key (int \1)) (dispatch! [:event/select-tab 0])
                 (= key (int \2)) (dispatch! [:event/select-tab 1])
                 (= key (int \3)) (dispatch! [:event/select-tab 2])))}
            (fn [state]
              (tty/block
                [(render-tabs state)
                 (render-tab-content state)
                 (render-keybindings)])))))
      (when-let [run-in-fg (::run-in-fg @app-state)]
        (swap! app-state dissoc ::run-in-fg nil)
        (run-in-fg)
        (recur)))))
