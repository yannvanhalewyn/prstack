(ns prstack.vcs.graph-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [prstack.vcs.graph :as graph]))

(deftest test-build-graph
  (testing "builds a simple linear graph"
    (let [nodes [{:change/change-id "trunk"
                  :change/parent-ids []
                  :change/local-branchnames ["main"]
                  :change/remote-branchnames ["main@origin"]}
                 {:change/change-id "feature-1"
                  :change/parent-ids ["trunk"]
                  :change/local-branchnames ["feature-1"]
                  :change/remote-branchnames []}
                 {:change/change-id "feature-2"
                  :change/parent-ids ["feature-1"]
                  :change/local-branchnames ["feature-2"]
                  :change/remote-branchnames []}]
          g (graph/build-graph nodes "trunk")]
      (is (= "trunk" (:graph/trunk-id g)))
      (is (= 3 (count (:graph/nodes g))))
      (is (= ["feature-1"] (get-in g [:graph/nodes "trunk" :change/children-ids])))
      (is (= ["feature-2"] (get-in g [:graph/nodes "feature-1" :change/children-ids])))
      (is (= [] (get-in g [:graph/nodes "feature-2" :change/children-ids])))))

  (testing "marks trunk and merge nodes correctly"
    (let [nodes [{:change/change-id "trunk"
                  :change/parent-ids []
                  :change/local-branchnames ["main"]
                  :change/remote-branchnames []}
                 {:change/change-id "feature"
                  :change/parent-ids ["trunk"]
                  :change/local-branchnames ["feature"]
                  :change/remote-branchnames []}
                 {:change/change-id "merge"
                  :change/parent-ids ["trunk" "feature"]
                  :change/local-branchnames ["merge"]
                  :change/remote-branchnames []}]
          g (graph/build-graph nodes "trunk")]
      (is (true? (get-in g [:graph/nodes "trunk" :change/trunk-node?])))
      (is (false? (get-in g [:graph/nodes "feature" :change/trunk-node?])))
      (is (false? (get-in g [:graph/nodes "feature" :change/merge-node?])))
      (is (true? (get-in g [:graph/nodes "merge" :change/merge-node?]))))))

(deftest test-find-path-to-trunk
  (testing "finds path from leaf to trunk"
    (let [g (graph/build-graph
              [{:change/change-id "trunk"
                :change/parent-ids []
                :change/local-branchnames ["main"]
                :change/remote-branchnames []}
               {:change/change-id "feature-1"
                :change/parent-ids ["trunk"]
                :change/local-branchnames ["feature-1"]
                :change/remote-branchnames []}
               {:change/change-id "feature-2"
                :change/parent-ids ["feature-1"]
                :change/local-branchnames ["feature-2"]
                :change/remote-branchnames []}]
              "trunk")
          paths (graph/find-all-paths-to-trunk g "feature-2")]
      (is (= [["feature-2" "feature-1" "trunk"]] paths))))

  (testing "handles merge nodes by following all parents"
    (let [g (graph/build-graph
              [{:change/change-id "trunk"
                :change/parent-ids []
                :change/local-branchnames ["main"]
                :change/remote-branchnames []}
               {:change/change-id "branch-a"
                :change/parent-ids ["trunk"]
                :change/local-branchnames ["branch-a"]
                :change/remote-branchnames []}
               {:change/change-id "branch-b"
                :change/parent-ids ["trunk"]
                :change/local-branchnames ["branch-b"]
                :change/remote-branchnames []}
               {:change/change-id "merge"
                :change/parent-ids ["branch-a" "branch-b"]
                :change/local-branchnames ["merge"]
                :change/remote-branchnames []}]
              "trunk")
          paths (graph/find-all-paths-to-trunk g "merge")]
      ;; find-all-paths-to-trunk returns ALL paths, not just first parent
      (is (= 2 (count paths)))
      (is (contains? (set paths) ["merge" "branch-a" "trunk"]))
      (is (contains? (set paths) ["merge" "branch-b" "trunk"])))))

(deftest test-find-all-paths-to-trunk
  (testing "finds all paths from merge node to trunk"
    (let [g (graph/build-graph
              [{:change/change-id "trunk"
                :change/parent-ids []
                :change/local-branchnames ["main"]
                :change/remote-branchnames []}
               {:change/change-id "branch-a"
                :change/parent-ids ["trunk"]
                :change/local-branchnames ["branch-a"]
                :change/remote-branchnames []}
               {:change/change-id "branch-b"
                :change/parent-ids ["trunk"]
                :change/local-branchnames ["branch-b"]
                :change/remote-branchnames []}
               {:change/change-id "merge"
                :change/parent-ids ["branch-a" "branch-b"]
                :change/local-branchnames ["merge"]
                :change/remote-branchnames []}]
              "trunk")
          paths (graph/find-all-paths-to-trunk g "merge")]
      (is (= 2 (count paths)))
      (is (contains? (set paths) ["merge" "branch-a" "trunk"]))
      (is (contains? (set paths) ["merge" "branch-b" "trunk"])))))

(deftest test-find-all-paths-with-custom-trunk
  (testing "finds paths to a custom trunk node when trunk has advanced"
    (let [;; Simulate trunk advancement: old-trunk -> new-trunk-1 -> new-trunk-2
          ;; and a stack forked from old-trunk: old-trunk -> feature-1 -> feature-2
          g (graph/build-graph
              [{:change/change-id "old-trunk"
                :change/parent-ids []
                :change/local-branchnames []
                :change/remote-branchnames []}
               {:change/change-id "new-trunk-1"
                :change/parent-ids ["old-trunk"]
                :change/local-branchnames []
                :change/remote-branchnames []}
               {:change/change-id "new-trunk-2"
                :change/parent-ids ["new-trunk-1"]
                :change/local-branchnames ["main"]
                :change/remote-branchnames []}
               {:change/change-id "feature-1"
                :change/parent-ids ["old-trunk"]
                :change/local-branchnames ["feature-1"]
                :change/remote-branchnames []}
               {:change/change-id "feature-2"
                :change/parent-ids ["feature-1"]
                :change/local-branchnames ["feature-2"]
                :change/remote-branchnames []}]
              "new-trunk-2")
          ;; Find path from feature-2 to the actual fork point (old-trunk)
          paths (graph/find-all-paths-to-trunk g "feature-2" "old-trunk")]
      (is (= 1 (count paths)))
      (is (= ["feature-2" "feature-1" "old-trunk"] (first paths))))))
