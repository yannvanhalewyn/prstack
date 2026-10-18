(ns prstack.tui.rows-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [prstack.tui.fixtures :refer [app-state new-pr repo-state stack]]
    [prstack.tui.rows :as rows]))

(defn- branches [rows]
  (keep #(when (= :change (:row/kind %))
           [(:repo/id %) (get-in % [:row/key :branch])])
    rows))

(deftest test-display-rows-single-repo
  (testing "no repo header when there is a single repo"
    (let [rows (rows/display-rows
                 (app-state (repo-state "api" [(stack "main" "feat/a" "feat/b")] [])))]
      (is (not-any? #(= :repo-header (:row/kind %)) rows))
      (is (= [["api" "feat/b"] ["api" "feat/a"]] (branches rows))))))

(deftest test-display-rows-multiple-repos
  (let [state (app-state
                (repo-state "web" [(stack "main" "feat/x")] [(new-pr 7 "feat/x" "main")])
                (repo-state "api" [(stack "main" "feat/x")] [(new-pr 3 "feat/x" "develop")]))
        rows (rows/display-rows state)]
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

(deftest test-display-rows-while-loading
  (let [rows-for (fn [stacks-load]
                   (map :row/kind
                     (rows/display-rows
                       (app-state
                         (assoc (repo-state "api" [] [])
                           :repo/current-stacks stacks-load)))))]
    (testing "shows that the repo is loading"
      (is (= [:loading :blank] (rows-for {:load/status :status/pending}))))

    (testing "shows the error when loading failed"
      (let [rows (rows/display-rows
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
      (let [row (->> (rows/display-rows
                       (app-state
                         (assoc (repo-state "api" [(stack "main" "feat/a")] [])
                           :repo/prs {:load/status :status/pending})))
                  (filter #(= :change (:row/kind %)))
                  first)]
        (is (nil? (:pr row)))
        (is (= :status/pending (get-in row [:prs-load :load/status])))))))

(deftest test-display-rows-collapses-repos-without-stacks
  (let [rows (rows/display-rows
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
          rows (rows/display-rows
                 (app-state
                   (reloading (repo-state "web" [(stack "main" "feat/x")] []))
                   (reloading (repo-state "docs" [] []))))]
      (is (= ["web"]
             (keep #(when (= :repo-header (:row/kind %)) (:repo/name %)) rows)))
      (is (= {:row/kind :text :style :gray :text "No stacks in: docs"}
             (last rows))))))
