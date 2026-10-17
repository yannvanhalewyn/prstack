(ns prstack.tui.db-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [prstack.tui.db :as db]))

(defn- change [branch type]
  {:change/change-id branch
   :change/selected-branchname branch
   :change/type type})

(defn- stack [& branches]
  (into [(change (first branches) :trunk)]
    (map #(change % :regular))
    (rest branches)))

(defn- new-pr [number head base]
  {:pr/number number :pr/head-branch head :pr/base-branch base
   :pr/url (str "https://example.com/" number)})

(defn- loaded [result]
  {:load/status :status/success :load/result result})

(defn- repo-state [id stacks prs]
  {:repo/id id
   :repo/name id
   :repo/load-token 1
   :repo/current-stacks (loaded stacks)
   :repo/all-stacks (loaded stacks)
   :repo/prs (loaded prs)})

(defn- app-state [& repos]
  {:app-state/selected-tab 0
   :app-state/repos (into {} (map (juxt :repo/id identity)) repos)})

(defn- branches [rows]
  (keep #(when (= :change (:row/kind %))
           [(:repo/id %) (get-in % [:row/key :branch])])
    rows))

(defn- move [state offset]
  (#'db/move-selection state offset))

(deftest test-display-rows-single-repo
  (testing "no repo header when there is a single repo"
    (let [rows (db/display-rows
                 (app-state (repo-state "api" [(stack "main" "feat/a" "feat/b")] [])))]
      (is (not-any? #(= :repo-header (:row/kind %)) rows))
      (is (= [["api" "feat/b"] ["api" "feat/a"]] (branches rows))))))

(deftest test-display-rows-multiple-repos
  (let [state (app-state
                (repo-state "web" [(stack "main" "feat/x")] [(new-pr 7 "feat/x" "main")])
                (repo-state "api" [(stack "main" "feat/x")] [(new-pr 3 "feat/x" "develop")]))
        rows (db/display-rows state)]
    (testing "repos are sorted by name, each with a header"
      (is (= ["api" "web"]
             (keep #(when (= :repo-header (:row/kind %)) (:repo/name %)) rows)))
      (is (= [["api" "feat/x"] ["web" "feat/x"]] (branches rows))))

    (testing "PRs are looked up in the row's own repo"
      (let [change-rows (filter #(= :change (:row/kind %)) rows)]
        (is (= [3 7] (map #(get-in % [:pr :pr/number]) change-rows)))
        (is (= ["main" nil] (map :wrong-base-branch change-rows)))))

    (testing "rows with the same branch in different repos have different keys"
      (is (apply distinct? (map :row/key (filter :row/key rows)))))))

(deftest test-selection
  (let [state (app-state
                (repo-state "api" [(stack "main" "feat/a" "feat/b")] [])
                (repo-state "web" [(stack "main" "feat/c")] []))]
    (testing "defaults to the first row"
      (is (= "feat/b" (get-in (db/selected-row state) [:row/key :branch]))))

    (testing "moves across repos and stays within the rows"
      (is (= ["web" "feat/c"]
             ((juxt :repo/id (comp :branch :row/key))
              (db/selected-row (move state 2)))))
      (is (= "feat/c" (get-in (db/selected-row (move state 10)) [:row/key :branch])))
      (is (= "feat/b" (get-in (db/selected-row (move (move state 10) -10)) [:row/key :branch]))))

    (testing "follows the selected row when rows before it change"
      (let [selected (move state 2)
            ;; A new stack in api pushes web's rows down
            refreshed (assoc-in selected [:app-state/repos "api"]
                        (repo-state "api" [(stack "main" "feat/a" "feat/b")
                                           (stack "main" "hotfix")] []))]
        (is (= ["web" "feat/c"]
               ((juxt :repo/id (comp :branch :row/key))
                (db/selected-row refreshed))))))

    (testing "falls back to the same branch in another stack of the same repo"
      (let [selected (move state 1) ; api feat/a in stack [main feat/a feat/b]
            ;; feat/b was removed, so feat/a's stack id changed
            refreshed (assoc-in selected [:app-state/repos "api"]
                        (repo-state "api" [(stack "main" "feat/a")] []))]
        (is (= ["api" "feat/a"]
               ((juxt :repo/id (comp :branch :row/key))
                (db/selected-row refreshed))))))

    (testing "falls back to the first row of the same repo when the branch is gone"
      (let [selected (move state 2) ; web feat/c
            refreshed (assoc-in selected [:app-state/repos "web"]
                        (repo-state "web" [(stack "main" "feat/d")] []))]
        (is (= ["web" "feat/d"]
               ((juxt :repo/id (comp :branch :row/key))
                (db/selected-row refreshed))))))))

(deftest test-display-rows-while-loading
  (let [rows-for (fn [stacks-load]
                   (map :row/kind
                     (db/display-rows
                       (app-state
                         (assoc (repo-state "api" [] [])
                           :repo/current-stacks stacks-load)))))]
    (testing "shows that the repo is loading"
      (is (= [:loading :blank] (rows-for {:load/status :status/pending}))))

    (testing "shows the error when loading failed"
      (let [rows (db/display-rows
                   (app-state
                     (repo-state "web" [(stack "main" "feat/x")] [])
                     (assoc (repo-state "api" [] [])
                       :repo/current-stacks {:load/status :status/failed
                                             :load/error {:error/message "boom"}})))]
        (is (= [:repo-header :blank :error :blank]
               (map :row/kind (take-while #(not= "web" (:repo/name %)) rows))))
        (is (= "boom" (:text (nth rows 2))))))

    (testing "keeps showing the previous stacks while reloading"
      (is (= [:stack-header :change :base]
             (rows-for {:load/status :status/pending
                        :load/result [(stack "main" "feat/a")]}))))

    (testing "shows PRs as being fetched"
      (let [row (->> (db/display-rows
                       (app-state
                         (assoc (repo-state "api" [(stack "main" "feat/a")] [])
                           :repo/prs {:load/status :status/pending})))
                  (filter #(= :change (:row/kind %)))
                  first)]
        (is (nil? (:pr row)))
        (is (= :status/pending (get-in row [:prs-load :load/status])))))))

(deftest test-update-loading-repo
  (let [state (app-state (repo-state "api" [] []))
        update-loading-repo #'db/update-loading-repo]
    (testing "applies updates of the current load"
      (is (= "new" (-> (update-loading-repo state "api" 1 assoc :repo/name "new")
                     (get-in [:app-state/repos "api" :repo/name])))))

    (testing "drops updates of a superseded load"
      (is (= state (update-loading-repo state "api" 0 assoc :repo/name "new"))))

    (testing "drops updates for repos that are gone"
      (is (= state (update-loading-repo state "web" 1 assoc :repo/name "new"))))))

(deftest test-display-rows-collapses-repos-without-stacks
  (let [rows (db/display-rows
               (app-state
                 (repo-state "web" [(stack "main" "feat/x")] [])
                 (repo-state "docs" [(stack "main")] [])
                 (repo-state "infra" [] [])
                 (assoc (repo-state "api" [] [])
                   :repo/current-stacks {:load/status :status/pending})))]
    (testing "only repos with stacks get a header"
      (is (= ["web"]
             (keep #(when (= :repo-header (:row/kind %)) (:repo/name %)) rows))))

    (testing "repos without stacks and loading repos are each listed on a single line"
      (is (= [{:row/kind :text :style :gray :text "No stacks in: docs, infra"}
              {:row/kind :loading :repo-names ["api"]}]
             (take-last 2 rows))))))

(deftest test-display-rows-while-reloading
  (testing "repos stay where their previous result put them"
    (let [reloading #(assoc-in % [:repo/current-stacks :load/status] :status/pending)
          rows (db/display-rows
                 (app-state
                   (reloading (repo-state "web" [(stack "main" "feat/x")] []))
                   (reloading (repo-state "docs" [] []))))]
      (is (= ["web"]
             (keep #(when (= :repo-header (:row/kind %)) (:repo/name %)) rows)))
      (is (= {:row/kind :text :style :gray :text "No stacks in: docs"}
             (last rows))))))
