(ns prstack.tui.rows
  "The TUI displays and navigates a flat list of rows across all repos. Rows
  are derived from the app state, see `prstack.tui.db/AppState`: the repos and
  the selected tab. They carry everything needed to render them and to act on
  them."
  (:require
    [clojure.string :as str]
    [prstack.pr :as pr]
    [prstack.stack :as stack]
    [prstack.utils :as u]))

(defn repos
  "Returns the repos in display order."
  [state]
  (sort-by :repo/name (vals (:app-state/repos state))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Display rows

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

(defn shows-all-stacks?
  "Returns whether the selected tab displays all stacks instead of the current
  ones."
  [state]
  (not= 0 (:app-state/selected-tab state)))

(defn- tab-stacks-load
  "Returns the load of the stacks of `repo` displayed on the selected tab."
  [state repo]
  (if (shows-all-stacks? state)
    (:repo/all-stacks repo)
    (:repo/current-stacks repo)))

(defn- split-tab-stacks
  "Splits stacks into `:regular-stacks` and `:feature-base-stacks`. For the
  'My Stacks' tab, stacks are sorted with non-feature-base stacks first, then
  feature-base stacks alphabetically."
  [state stacks]
  (stack/split-feature-base-stacks
    (if (= 1 (:app-state/selected-tab state))
      (sort-stacks-by-base stacks)
      stacks)))

(defn- stack-rows
  "Returns a row for every change in `stack` from leaf to base, where the base
  change is not selectable."
  [repo stack]
  (let [repo-id (:repo/id repo)
        stack-id (stack/id stack)
        prs-load (:repo/prs repo)
        prs (:load/result prs-load)
        changes (reverse stack)]
    (concat
      (for [[change base] (u/consecutive-pairs changes)]
        (let [branch (:change/selected-branchname change)
              base-branch (:change/selected-branchname base)
              pr (when prs (pr/find-pr prs branch))]
          {:row/kind :change
           :row/key {:repo/id repo-id :stack/id stack-id :branch branch}
           :repo/id repo-id
           :change change
           :base base
           :pr pr
           :prs-load prs-load
           ;; Set when the PR has the wrong base branch
           :wrong-base-branch (when (and pr (not= (:pr/base-branch pr) base-branch))
                                base-branch)}))
      [{:row/kind :base
        :repo/id repo-id
        :change (last changes)}])))

(defn- stacks-rows [state repo stacks]
  (let [{:keys [regular-stacks feature-base-stacks]} (split-tab-stacks state stacks)]
    (concat
      ;; When every stack has only the trunk node, that means no stacks
      (if (stack/any-segments? regular-stacks)
        (mapcat
          (fn [[i stack]]
            (concat
              ;; TODO better detect current stack in megamerges for example
              [{:row/kind :stack-header
                :label (if (zero? i) "Current Stack" "Other Stack")
                :size (dec (count stack))}]
              (stack-rows repo stack)
              ;; Add blank line between stacks
              (when-not (and (= i (dec (count regular-stacks)))
                             (empty? feature-base-stacks))
                [{:row/kind :blank}])))
          (u/indexed regular-stacks))
        [{:row/kind :text :style :cyan :text "No stacks detetected"}
         {:row/kind :text :text "Create some branches to see some stacks appear."}
         {:row/kind :blank}])
      (when (seq feature-base-stacks)
        (concat
          [{:row/kind :section-header :text " Feature Base Branches"}]
          (mapcat #(concat (stack-rows repo %) [{:row/kind :blank}])
            feature-base-stacks))))))

(defn- repo-rows [state repo]
  (let [{:load/keys [status result error]} (tab-stacks-load state repo)]
    (cond
      ;; Also while reloading, as long as there is a previous result
      (or (= :status/success status) (some? result))
      (stacks-rows state repo (or result []))

      (= :status/failed status)
      [{:row/kind :error :text (:error/message error)}
       {:row/kind :blank}]

      :else
      [{:row/kind :loading}
       {:row/kind :blank}])))

(defn- repo-display
  "Returns how `repo` is displayed among multiple repos, based on the stacks
  displayed on the selected tab:
    :repo.display/stacks  - under a header with its stacks
    :repo.display/failed  - under a header with the error
    :repo.display/empty   - listed with the other repos without stacks
    :repo.display/loading - listed with the other repos that are loading

  While reloading, the previous result decides, so repos don't move around."
  [state repo]
  (let [{:load/keys [status result]} (tab-stacks-load state repo)]
    (cond
      (or (= :status/success status) (some? result))
      (let [{:keys [regular-stacks feature-base-stacks]}
            (split-tab-stacks state (or result []))]
        (if (or (stack/any-segments? regular-stacks) (seq feature-base-stacks))
          :repo.display/stacks
          :repo.display/empty))

      (= :status/failed status) :repo.display/failed
      :else :repo.display/loading)))

(defn- multiple-repo-rows
  "Returns the rows for multiple repos. Repos with stacks or that failed to load
  are shown under a header. Repos without stacks and repos that are still
  loading are each listed on a single line at the bottom, so repos only get a
  header once there is something to show."
  [state repos]
  (let [{stacks-repos :repo.display/stacks
         failed-repos :repo.display/failed
         empty-repos :repo.display/empty
         loading-repos :repo.display/loading}
        (group-by #(repo-display state %) repos)
        names #(str/join ", " (map :repo/name %))]
    (concat
      (mapcat
        (fn [repo]
          (let [rows (repo-rows state repo)]
            (concat
              [{:row/kind :repo-header
                :repo/id (:repo/id repo)
                :repo/name (:repo/name repo)}
               {:row/kind :blank}]
              rows
              ;; Separate repos with a blank line
              (when-not (= :blank (:row/kind (last rows)))
                [{:row/kind :blank}]))))
        ;; Keep the display order
        (filter (set (concat stacks-repos failed-repos)) repos))
      (when (seq empty-repos)
        [{:row/kind :text
          :style :gray
          :text (str "No stacks in: " (names empty-repos))}])
      (when (seq loading-repos)
        [{:row/kind :loading
          :repo-names (map :repo/name loading-repos)}]))))

(defn display-rows
  "Returns the rows displayed for all repos. Repo headers are only shown when
  there are multiple repos."
  [state]
  (let [repos (repos state)]
    (vec
      (if (> (count repos) 1)
        (multiple-repo-rows state repos)
        (mapcat #(repo-rows state %) repos)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Selection

(defn selectable-rows
  "Returns the rows that can be selected."
  [rows]
  (filterv #(= :change (:row/kind %)) rows))

(defn resolve-selection
  "Returns the row of `selectable-rows` matching `selection`. The selected row
  can disappear after a refresh or a tab switch, so falls back to the same
  branch in another stack of the same repo, then to the first row of that repo
  and finally to the first row."
  [selectable-rows selection]
  (let [{repo-id :repo/id branch :branch} selection
        same-repo? #(= (:repo/id %) repo-id)]
    (or (u/find-first #(= (:row/key %) selection) selectable-rows)
        (u/find-first #(and (same-repo? %)
                            (= (get-in % [:row/key :branch]) branch))
          selectable-rows)
        (u/find-first same-repo? selectable-rows)
        (first selectable-rows))))

(defn selected-row
  "Returns the selected row, or nil when nothing can be selected. Pass `rows`
  when display rows were already computed."
  ([state]
   (selected-row state (display-rows state)))
  ([state rows]
   (resolve-selection (selectable-rows rows) (:app-state/selection state))))
