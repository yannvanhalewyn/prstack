(ns prstack.tui.db
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [bb-tty.tui :as tui]
    [clojure.java.browse :as browse]
    [prstack.cli.commands.sync :as commands.sync]
    [prstack.config :as config]
    [prstack.github :as github]
    [prstack.stack :as stack]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defonce app-state
  (atom {:app-state/selected-item-idx 0
         :app-state/prs {}
         :app-state/selected-tab 0}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Selectors

(defn- assoc-ui-indices
  "Takes a list of stacks, and assoc's a `:ui/idx` key to every change in each
  stack in order."
  [stacks]
  (second
    (reduce
      (fn [[idx ret] stack]
        (let [[idx stack]
              (reduce (fn [[idx ret] change]
                        (if (= change (last stack))
                          [idx (conj ret change)]
                          [(inc idx) (conj ret (assoc change :ui/idx idx))]))
                [idx []]
                stack)]
          [idx (conj ret stack)]))
      [0 []]
      stacks)))

(defn- largest-ui-index [stacks]
  (when-let [indexes (seq (keep :ui/idx (stack/leaves stacks)))]
    (apply max indexes)))

(comment
  (assoc-ui-indices
    [[{:change/local-branches ["main"]}
      {:change/local-branches ["feature-a"]}]
     [{:change/local-branches ["main"]}
      {:change/local-branches ["hotfix"]}]]))

(defn displayed-stacks [state]
  (assoc-ui-indices
    (stack/reverse-stacks
      (case (:app-state/selected-tab state)
        0 @(:app-state/current-stacks state)
        1 @(:app-state/all-stacks state)
        @(:app-state/all-stacks state)))))

(defn selected-and-prev-change [state]
  (let [leaves (stack/leaves (displayed-stacks state))
        idx (:app-state/selected-item-idx state)
        pairs (u/consecutive-pairs leaves)]
    (when-let [[selected-change prev-change]
               (u/find-first #(= (:ui/idx (first %)) idx) pairs)]
      {:selected-change selected-change
       :prev-change prev-change})))

(defn pr-path [head-branch base-branch]
  [:app-state/prs head-branch base-branch])

(defn find-pr [state head-branch base-branch]
  (get-in state (pr-path head-branch base-branch)))

(defn current-pr [state]
  (when-let [{:keys [selected-change prev-change]}
             (selected-and-prev-change state)]
    (find-pr state
      (vcs/local-branchname selected-change)
      (vcs/local-branchname prev-change))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events

(defmulti dispatch! (fn [[evt]] evt))

(defmethod dispatch! :event/read-local-repo
  [_evt]
  (let [config (config/read-local)
        vcs-config (vcs/config)]
    (spit "target/prstack.log" "Reading local REPO\n")
    (swap! app-state merge
      {:app-state/config config
       :app-state/vcs-config vcs-config
       :app-state/current-stacks (delay (stack/get-current-stacks vcs-config config))
       :app-state/all-stacks (delay (stack/get-all-stacks vcs-config config))})))

(defmethod dispatch! :event/fetch-pr
  [[_ head-branch base-branch]]
  (when base-branch
    (swap! app-state assoc-in (pr-path head-branch base-branch)
      {:http/status :status/pending})
    (future
      (swap! app-state assoc-in (pr-path head-branch base-branch)
        (or (github/find-pr head-branch base-branch)
            {:missing true})))))

(defmethod dispatch! :event/refresh
  [_evt]
  (dispatch! [:event/read-local-repo])
  (doseq [stack (displayed-stacks @app-state)]
    (doseq [[cur-change prev-change] (u/consecutive-pairs stack)]
      (when prev-change
        (dispatch! [:event/fetch-pr
                    (vcs/local-branchname cur-change)
                    (vcs/local-branchname prev-change)])))))

(defmethod dispatch! :event/run-diff
  [_evt]
  (when-let [{:keys [selected-change prev-change]}
             (selected-and-prev-change @app-state)]
    (swap! app-state assoc :app-state/run-in-fg
      #(u/shell-out
         [(System/getenv "EDITOR") "-c"
          (format "Difft %s..%s"
            (or (:change/commit-sha prev-change)
                (vcs/local-branchname prev-change))
            (:change/commit-sha selected-change))]))
    (tui/close!)))

(defmethod dispatch! :event/open-pr
  [_evt]
  (when-let [url (:pr/url (current-pr @app-state))]
    (browse/browse-url url)))

(defmethod dispatch! :event/create-pr
  [_evt]
  (when-let [{:keys [selected-change prev-change]}
             (and (not (:pr/url (current-pr @app-state)))
                  (selected-and-prev-change @app-state))]
    (let [head-branch (vcs/local-branchname selected-change)
          base-branch (vcs/local-branchname prev-change)]
      (swap! app-state assoc :app-state/run-in-fg
        (fn []
          (println "Creating PR for"
            (ansi/colorize :blue head-branch)
            " onto "
            (ansi/colorize :blue base-branch))
          (github/create-pr! head-branch base-branch)))
      (tui/close!))))

(defmethod dispatch! :event/merge-pr
  [_evt]
  (let [current-pr (current-pr @app-state)]
    (when (and current-pr (not (:missing current-pr)))
      (swap! app-state assoc :app-state/run-in-fg
        (fn []
          (when (tty/prompt-yes
                  (format "Would you like to merge PR %s %s?\nThis will merge %s onto %s."
                    (ansi/colorize :blue (str "#" (:pr/number current-pr)))
                    (:pr/title current-pr)
                    ;; TODO get actual PRs branches
                    (ansi/colorize :blue "HEAD")
                    (ansi/colorize :blue "BASE")))
            (github/merge-pr! (:pr/number current-pr))
            ((:exec commands.sync/command) []))))
      (tui/close!))))

(defmethod dispatch! :event/sync
  [_evt]
  (swap! app-state assoc :app-state/run-in-fg
    #((:exec commands.sync/command) []))
  (tui/close!))

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
  (swap! app-state update :app-state/selected-item-idx
    #(max 0 (dec %))))

(defmethod dispatch! :event/move-down
  [_evt]
  (when-let [largest-idx (largest-ui-index (displayed-stacks @app-state))]
    (swap! app-state update :app-state/selected-item-idx
      #(min largest-idx (inc %)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscriptions

(defn sub-pr [head-branch base-branch]
  (when base-branch
    (let [pr-info (get-in @app-state (pr-path head-branch base-branch))]
      (when-not pr-info
        (dispatch! [:event/fetch-pr head-branch base-branch]))
      pr-info)
    (get-in @app-state (pr-path head-branch base-branch))))

(comment
  (displayed-stacks @app-state))
