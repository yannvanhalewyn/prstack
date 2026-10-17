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

(defn- repo-state [id stacks prs]
  {:repo/id id
   :repo/name id
   :repo/current-stacks (delay stacks)
   :repo/all-stacks (delay stacks)
   :repo/prs {:http/status :status/success :http/result prs}})

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
