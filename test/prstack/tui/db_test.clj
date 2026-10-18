(ns prstack.tui.db-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [prstack.tui.db :as db]
    [prstack.tui.fixtures :refer [app-state repo-state stack]]
    [prstack.tui.rows :as rows]))

(defn- move [state offset]
  (#'db/move-selection state offset))

(deftest test-selection
  (let [state (app-state
                (repo-state "api" [(stack "main" "feat/a" "feat/b")] [])
                (repo-state "web" [(stack "main" "feat/c")] []))]
    (testing "defaults to the first row"
      (is (= "feat/b" (get-in (rows/selected-row state) [:row/key :branch]))))

    (testing "moves across repos and stays within the rows"
      (is (= ["web" "feat/c"]
             ((juxt :repo/id (comp :branch :row/key))
              (rows/selected-row (move state 2)))))
      (is (= "feat/c" (get-in (rows/selected-row (move state 10)) [:row/key :branch])))
      (is (= "feat/b" (get-in (rows/selected-row (move (move state 10) -10)) [:row/key :branch]))))

    (testing "follows the selected row when rows before it change"
      (let [selected (move state 2)
            ;; A new stack in api pushes web's rows down
            refreshed (assoc-in selected [:app-state/repos "api"]
                        (repo-state "api" [(stack "main" "feat/a" "feat/b")
                                           (stack "main" "hotfix")] []))]
        (is (= ["web" "feat/c"]
               ((juxt :repo/id (comp :branch :row/key))
                (rows/selected-row refreshed))))))

    (testing "falls back to the same branch in another stack of the same repo"
      (let [selected (move state 1) ; api feat/a in stack [main feat/a feat/b]
            ;; feat/b was removed, so feat/a's stack id changed
            refreshed (assoc-in selected [:app-state/repos "api"]
                        (repo-state "api" [(stack "main" "feat/a")] []))]
        (is (= ["api" "feat/a"]
               ((juxt :repo/id (comp :branch :row/key))
                (rows/selected-row refreshed))))))

    (testing "falls back to the first row of the same repo when the branch is gone"
      (let [selected (move state 2) ; web feat/c
            refreshed (assoc-in selected [:app-state/repos "web"]
                        (repo-state "web" [(stack "main" "feat/d")] []))]
        (is (= ["web" "feat/d"]
               ((juxt :repo/id (comp :branch :row/key))
                (rows/selected-row refreshed))))))))

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
