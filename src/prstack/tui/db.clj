(ns prstack.tui.db
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [bb-tty.tui :as tui]
    [clojure.java.browse :as browse]
    [prstack.cli.commands.sync :as commands.sync]
    [prstack.config :as config]
    [prstack.github :as github]
    [prstack.pr :as pr]
    [prstack.stack :as stack]
    [prstack.system :as system]
    [prstack.utils :as u]))

(defn- http-request-schema [result-schema]
  [:map
   [:htt/status [:enum :status/pending :status/failed :status/success]]
   [:http/result result-schema]
   [:http/error
    [:map [:error/type :keyword
           [:error/message :string]]]]])

(def ^:lsp/allow-unused AppState
  [:map
   ;; System
   [:app-state/system system/System]

   ;; Stacks
   [:app-state/current-stacks [:fn #(instance? clojure.lang.Delay %)]]
   [:app-state/all-stacks [:fn #(instance? clojure.lang.Delay %)]]
   [:app-state/prs (http-request-schema [:sequential github/PR])]

   ;; UI
   [:app-state/selected-tab :int]
   [:app-state/selected-item-idx :int]
   [:app-state/run-in-fg :fn]])

(defonce app-state
  (atom {:app-state/selected-item-idx 0
         :app-state/selected-tab 0}))

(defn vcs [state]
  (get-in state [:app-state/system :system/vcs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Displaying Stacks

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

(comment
  (assoc-ui-indices
    [[{:change/local-branchnames ["main"]}
      {:change/local-branchnames ["feature-a"]}]
     [{:change/local-branchnames ["main"]}
      {:change/local-branchnames ["hotfix"]}]]))

(defn displayed-stacks
  "Returns a map with :regular-stacks and :feature-base-stacks,
   both with UI indices and reversed for display.

   UI indices are assigned sequentially across both regular and feature-base stacks
   to ensure only one item is selected at a time.

   For the 'All Stacks' tab, stacks are sorted with non-feature-base stacks first,
   then feature-base stacks alphabetically."
  [state]
  (let [raw-stacks (case (:app-state/selected-tab state)
                     0 @(:app-state/current-stacks state)
                     1 @(:app-state/all-stacks state)
                     @(:app-state/all-stacks state))
        ;; Sort stacks for "All Stacks" tab (tab 1)
        sorted-raw-stacks (if (= 1 (:app-state/selected-tab state))
                            (sort-stacks-by-base raw-stacks)
                            raw-stacks)
        {:keys [regular-stacks feature-base-stacks]}
        (stack/split-feature-base-stacks sorted-raw-stacks)
        ;; Reverse both stack groups, then index them as a single sequence for
        ;; the UI
        all-indexed (assoc-ui-indices (concat
                                        (stack/reverse-stacks regular-stacks)
                                        (stack/reverse-stacks feature-base-stacks)))
        [indexed-regular indexed-feature-base] (split-at (count regular-stacks) all-indexed)]
    {:regular-stacks (vec indexed-regular)
     :feature-base-stacks (vec indexed-feature-base)}))

(defn all-displayed-stacks
  "Returns all stacks (regular + feature-base) as a flat sequence for operations
   that need to work across all stacks (like navigation and PR fetching)."
  [state]
  (let [{:keys [regular-stacks feature-base-stacks]} (displayed-stacks state)]
    (concat regular-stacks feature-base-stacks)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pull Requests

(defn selected-and-prev-change [state]
  (let [leaves (stack/leaves (all-displayed-stacks state))
        idx (:app-state/selected-item-idx state)
        pairs (u/consecutive-pairs leaves)]
    (when-let [[selected-change prev-change]
               (u/find-first #(= (:ui/idx (first %)) idx) pairs)]
      {:selected-change selected-change
       :prev-change prev-change})))

(defn find-pr [state head-branch base-branch]
  (pr/find-pr (get-in state [:app-state/prs :http/result])
    head-branch base-branch))

(defn current-pr [state]
  (when-let [{:keys [selected-change prev-change]}
             (selected-and-prev-change state)]
    (find-pr state
      (:change/selected-branchname selected-change)
      (:change/selected-branchname prev-change))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events

(defmulti dispatch! (fn [[evt]] evt))

(defmethod dispatch! :event/read-local-repo
  [_evt]
  (let [system (system/new (config/read-local))]
    (swap! app-state merge
      {:app-state/system system
       :app-state/current-stacks (delay (stack/get-current-stacks system))
       :app-state/all-stacks (delay (stack/get-all-stacks system))})))

(defmethod dispatch! :event/fetch-prs
  [_evt]
  (swap! app-state assoc :app-state/prs
    {:http/status :status/pending})
  (future
    (let [[result err] (github/list-prs)]
      (swap! app-state assoc :app-state/prs
        (if err
          {:http/status :status/failed
           :http/error err}
          {:http/status :status/success
           :http/result result})))))

(defmethod dispatch! :event/refresh
  [_evt]
  (dispatch! [:event/read-local-repo])
  (dispatch! [:event/fetch-prs]))

(defmethod dispatch! :event/run-diff
  [_evt]
  (when-let [{:keys [selected-change prev-change]}
             (selected-and-prev-change @app-state)]
    (let [global-config (get-in @app-state [:app-state/system :system/global-config])
          from-sha (or (:change/commit-sha prev-change)
                       (:change/selected-branchname prev-change))
          to-sha (:change/commit-sha selected-change)
          {:keys [strategy value]} (config/get-diffview-cmd global-config
                                     from-sha to-sha)]
      (swap! app-state assoc :app-state/run-in-fg
        (case strategy
          :pipe #(u/pipeline value {:inherit-last true})
          :shell #(u/shell-out value)))
      (tui/close!))))

(defmethod dispatch! :event/open-pr
  [_evt]
  (when-let [url (:pr/url (current-pr @app-state))]
    (browse/browse-url url)))

(defmethod dispatch! :event/create-pr
  [_evt]
  (when-let [{:keys [selected-change prev-change]}
             (and (not (:pr/url (current-pr @app-state)))
                  (selected-and-prev-change @app-state))]
    (let [head-branch (:change/selected-branchname selected-change)
          base-branch (:change/selected-branchname prev-change)]
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
  (let [current-pr (current-pr @app-state)
        {:keys [selected-change prev-change]}
        (selected-and-prev-change @app-state)]
    (when current-pr
      (swap! app-state assoc :app-state/run-in-fg
        (fn []
          (when (tty/prompt-confirm
                  {:prompt
                   (format "Would you like to merge PR %s %s?\nThis will merge %s onto %s."
                     (ansi/colorize :blue (str "#" (:pr/number current-pr)))
                     (:pr/title current-pr)
                     (ansi/colorize :blue (:change/selected-branchname selected-change))
                     (ansi/colorize :blue (:change/selected-branchname prev-change)))})
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
  (when-let [largest-idx (largest-ui-index (all-displayed-stacks @app-state))]
    (swap! app-state update :app-state/selected-item-idx
      #(min largest-idx (inc %)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscriptions

(defn sub-pr [head-branch base-branch]
  [(find-pr @app-state head-branch base-branch)
   (:app-state/prs @app-state)])

(comment
  (displayed-stacks @app-state))
