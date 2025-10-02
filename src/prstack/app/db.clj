(ns prstack.app.db
  (:require
    [clojure.java.browse :as browse]
    [prstack.commands.sync :as commands.sync]
    [prstack.stack :as stack]
    [prstack.tty :as tty]
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
                        [(inc idx) (conj ret (assoc change :ui/idx idx))])
                [idx []]
                stack)]
          [idx (conj ret stack)]))
      [0 []]
      stacks)))

(comment
  (assoc-ui-indices
    [[{:change/local-bookmarks ["main"]}
      {:change/local-bookmarks ["feature-a"]}]
     [{:change/local-bookmarks ["main"]}
      {:change/local-bookmarks ["hotfix"]}]]))

(defn displayed-stacks [state]
  (assoc-ui-indices
    (stack/reverse-stacks
      (case (:app-state/selected-tab state)
        0 @(:app-state/current-stacks state)
        1 @(:app-state/all-stacks state)
        @(:app-state/all-stacks state)))))

(defn selected-and-prev-change [state]
  (let [leaves (stack/leaves (displayed-stacks state))
        idx (:app-state/selected-item-idx state)]
    (when (< idx (dec (count leaves)))
      {:selected-change (nth leaves idx)
       :prev-change (nth leaves (inc idx))})))

(defn find-pr [state head-branch base-branch]
  (get-in state [:app-state/prs head-branch base-branch]))

(defn current-pr-url [state]
  (when-let [{:keys [selected-change prev-change]}
             (selected-and-prev-change state)]
    (:pr/url
      (find-pr state
        (vcs/local-branchname selected-change)
        (vcs/local-branchname prev-change)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events

(defmulti dispatch! (fn [[evt]] evt))

(defmethod dispatch! :event/fetch-pr
  [[_ head-branch base-branch]]
  (when-not (find-pr @app-state head-branch base-branch)
    (future
      (swap! app-state update :app-state/prs assoc-in [head-branch base-branch]
        {:pr/url (vcs/find-pr head-branch base-branch)}))))

(defmethod dispatch! :event/run-diff
  [_evt]
  (when-let [{:keys [selected-change prev-change]}
             (selected-and-prev-change @app-state)]
    (swap! app-state assoc :app-state/run-in-fg
      #(u/shell-out
         [(System/getenv "EDITOR") "-c"
          (format "DiffviewOpen %s...%s"
            (or (:change/commit-sha prev-change)
                (vcs/local-branchname prev-change))
            (:change/commit-sha selected-change))]))
    (tty/close!)))

(defmethod dispatch! :event/open-pr
  [_evt]
  (when-let [url (current-pr-url @app-state)]
    (browse/browse-url url)))

(defmethod dispatch! :event/sync
  [_evt]
  (swap! app-state assoc :app-state/run-in-fg #((:exec commands.sync/command) []))
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
