(ns prstack.stack-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [prstack.stack :as stack]
    [prstack.vcs.graph :as graph]))

(deftest test-path->stack-advanced-trunk
  (testing "fork-point with no bookmark gets trunk branch name"
    (let [nodes [{:change/change-id "fork-point"
                  :change/parent-ids []
                  :change/local-branchnames []
                  :change/remote-branchnames []}
                 {:change/change-id "trunk-tip"
                  :change/parent-ids ["fork-point"]
                  :change/local-branchnames ["main"]
                  :change/remote-branchnames []
                  :change/selected-branchname "main"}
                 {:change/change-id "feature-a"
                  :change/parent-ids ["fork-point"]
                  :change/local-branchnames ["feature-a"]
                  :change/remote-branchnames []
                  :change/selected-branchname "feature-a"}]
          vcs-graph (graph/build-graph nodes "trunk-tip" {})
          path ["feature-a" "fork-point"]
          result (stack/path->stack path vcs-graph "main")]
      ;; Stack is reversed: [fork-point, feature-a]
      (is (= 2 (count result)))
      (is (= "main" (:change/selected-branchname (first result))))
      (is (= :trunk (:change/type (first result))))
      (is (= "feature-a" (:change/selected-branchname (second result)))))))

(deftest test-path->stack-no-divergence
  (testing "when fork-point equals trunk, original bookmark is preserved"
    (let [nodes [{:change/change-id "trunk"
                  :change/parent-ids []
                  :change/local-branchnames ["main"]
                  :change/remote-branchnames []
                  :change/selected-branchname "main"}
                 {:change/change-id "feature-a"
                  :change/parent-ids ["trunk"]
                  :change/local-branchnames ["feature-a"]
                  :change/remote-branchnames []
                  :change/selected-branchname "feature-a"}]
          vcs-graph (graph/build-graph nodes "trunk" {})
          path ["feature-a" "trunk"]
          result (stack/path->stack path vcs-graph "main")]
      (is (= 2 (count result)))
      ;; Trunk node keeps its original bookmark, not reassigned
      (is (= "main" (:change/selected-branchname (first result))))
      ;; build-graph assigns :trunk type to the trunk node
      (is (= :trunk (:change/type (first result))))
      (is (= "feature-a" (:change/selected-branchname (second result)))))))

(deftest test-path->stack-deeply-nested
  (testing "deeply nested stack with advanced trunk includes all nodes"
    (let [nodes [{:change/change-id "fork-point"
                  :change/parent-ids []
                  :change/local-branchnames []
                  :change/remote-branchnames []}
                 {:change/change-id "trunk-tip"
                  :change/parent-ids ["fork-point"]
                  :change/local-branchnames ["main"]
                  :change/remote-branchnames []
                  :change/selected-branchname "main"}
                 {:change/change-id "feature-a"
                  :change/parent-ids ["fork-point"]
                  :change/local-branchnames ["feature-a"]
                  :change/remote-branchnames []
                  :change/selected-branchname "feature-a"}
                 {:change/change-id "feature-b"
                  :change/parent-ids ["feature-a"]
                  :change/local-branchnames ["feature-b"]
                  :change/remote-branchnames []
                  :change/selected-branchname "feature-b"}
                 {:change/change-id "feature-c"
                  :change/parent-ids ["feature-b"]
                  :change/local-branchnames ["feature-c"]
                  :change/remote-branchnames []
                  :change/selected-branchname "feature-c"}]
          vcs-graph (graph/build-graph nodes "trunk-tip" {})
          path ["feature-c" "feature-b" "feature-a" "fork-point"]
          result (stack/path->stack path vcs-graph "main")]
      ;; Stack should have all 4 nodes: fork-point, feature-a, feature-b, feature-c
      (is (= 4 (count result)))
      ;; Verify order (trunk to leaf)
      (is (= "main" (:change/selected-branchname (nth result 0))))
      (is (= :trunk (:change/type (nth result 0))))
      (is (= "feature-a" (:change/selected-branchname (nth result 1))))
      (is (= "feature-b" (:change/selected-branchname (nth result 2))))
      (is (= "feature-c" (:change/selected-branchname (nth result 3)))))))

(deftest test-path->stack-nil-node-guard
  (testing "path with nonexistent node filters it out"
    (let [nodes [{:change/change-id "trunk"
                  :change/parent-ids []
                  :change/local-branchnames ["main"]
                  :change/remote-branchnames []
                  :change/selected-branchname "main"}]
          vcs-graph (graph/build-graph nodes "trunk" {})
          path ["nonexistent-id" "trunk"]
          result (stack/path->stack path vcs-graph "main")]
      ;; Nonexistent nodes are filtered out (get-node returns nil, filter removes it)
      ;; Only the trunk node remains
      (is (= 1 (count result)))
      (is (= "main" (:change/selected-branchname (first result)))))))
