(ns prstack.vcs.graph-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [prstack.vcs.graph :as graph]))

(deftest test-build-graph
  (testing "builds a simple linear graph"
    (let [nodes [{:node/change-id "trunk"
                  :node/parents []
                  :node/local-branches ["main"]
                  :node/remote-branches ["main@origin"]}
                 {:node/change-id "feature-1"
                  :node/parents ["trunk"]
                  :node/local-branches ["feature-1"]
                  :node/remote-branches []}
                 {:node/change-id "feature-2"
                  :node/parents ["feature-1"]
                  :node/local-branches ["feature-2"]
                  :node/remote-branches []}]
          g (graph/build-graph nodes "trunk")]
      (is (= "trunk" (:graph/trunk-id g)))
      (is (= 3 (count (:graph/nodes g))))
      (is (= ["feature-1"] (get-in g [:graph/nodes "trunk" :node/children])))
      (is (= ["feature-2"] (get-in g [:graph/nodes "feature-1" :node/children])))
      (is (= [] (get-in g [:graph/nodes "feature-2" :node/children])))))
  
  (testing "marks trunk and merge nodes correctly"
    (let [nodes [{:node/change-id "trunk"
                  :node/parents []
                  :node/local-branches ["main"]
                  :node/remote-branches []}
                 {:node/change-id "feature"
                  :node/parents ["trunk"]
                  :node/local-branches ["feature"]
                  :node/remote-branches []}
                 {:node/change-id "merge"
                  :node/parents ["trunk" "feature"]
                  :node/local-branches ["merge"]
                  :node/remote-branches []}]
          g (graph/build-graph nodes "trunk")]
      (is (true? (get-in g [:graph/nodes "trunk" :node/is-trunk?])))
      (is (false? (get-in g [:graph/nodes "feature" :node/is-trunk?])))
      (is (false? (get-in g [:graph/nodes "feature" :node/is-merge?])))
      (is (true? (get-in g [:graph/nodes "merge" :node/is-merge?]))))))

(deftest test-leaf-nodes
  (testing "finds leaf nodes in a graph"
    (let [g (graph/build-graph
              [{:node/change-id "trunk"
                :node/parents []
                :node/local-branches ["main"]
                :node/remote-branches []}
               {:node/change-id "feature-1"
                :node/parents ["trunk"]
                :node/local-branches ["feature-1"]
                :node/remote-branches []}
               {:node/change-id "feature-2"
                :node/parents ["trunk"]
                :node/local-branches ["feature-2"]
                :node/remote-branches []}]
              "trunk")
          leaves (graph/leaf-nodes g)]
      (is (= 2 (count leaves)))
      (is (contains? (set (map :node/change-id leaves)) "feature-1"))
      (is (contains? (set (map :node/change-id leaves)) "feature-2")))))

(deftest test-find-path-to-trunk
  (testing "finds path from leaf to trunk"
    (let [g (graph/build-graph
              [{:node/change-id "trunk"
                :node/parents []
                :node/local-branches ["main"]
                :node/remote-branches []}
               {:node/change-id "feature-1"
                :node/parents ["trunk"]
                :node/local-branches ["feature-1"]
                :node/remote-branches []}
               {:node/change-id "feature-2"
                :node/parents ["feature-1"]
                :node/local-branches ["feature-2"]
                :node/remote-branches []}]
              "trunk")
          path (graph/find-path-to-trunk g "feature-2")]
      (is (= ["feature-2" "feature-1" "trunk"] path))))
  
  (testing "handles merge nodes by following first parent"
    (let [g (graph/build-graph
              [{:node/change-id "trunk"
                :node/parents []
                :node/local-branches ["main"]
                :node/remote-branches []}
               {:node/change-id "branch-a"
                :node/parents ["trunk"]
                :node/local-branches ["branch-a"]
                :node/remote-branches []}
               {:node/change-id "branch-b"
                :node/parents ["trunk"]
                :node/local-branches ["branch-b"]
                :node/remote-branches []}
               {:node/change-id "merge"
                :node/parents ["branch-a" "branch-b"]
                :node/local-branches ["merge"]
                :node/remote-branches []}]
              "trunk")
          path (graph/find-path-to-trunk g "merge")]
      (is (= ["merge" "branch-a" "trunk"] path)))))

(deftest test-find-all-paths-to-trunk
  (testing "finds all paths from merge node to trunk"
    (let [g (graph/build-graph
              [{:node/change-id "trunk"
                :node/parents []
                :node/local-branches ["main"]
                :node/remote-branches []}
               {:node/change-id "branch-a"
                :node/parents ["trunk"]
                :node/local-branches ["branch-a"]
                :node/remote-branches []}
               {:node/change-id "branch-b"
                :node/parents ["trunk"]
                :node/local-branches ["branch-b"]
                :node/remote-branches []}
               {:node/change-id "merge"
                :node/parents ["branch-a" "branch-b"]
                :node/local-branches ["merge"]
                :node/remote-branches []}]
              "trunk")
          paths (graph/find-all-paths-to-trunk g "merge")]
      (is (= 2 (count paths)))
      (is (contains? (set paths) ["merge" "branch-a" "trunk"]))
      (is (contains? (set paths) ["merge" "branch-b" "trunk"])))))

(deftest test-path->stack
  (testing "converts path to stack (reversed, trunk first)"
    (let [g (graph/build-graph
              [{:node/change-id "trunk"
                :node/commit-sha "abc123"
                :node/parents []
                :node/local-branches ["main"]
                :node/remote-branches ["main@origin"]}
               {:node/change-id "feature-1"
                :node/commit-sha "def456"
                :node/parents ["trunk"]
                :node/local-branches ["feature-1"]
                :node/remote-branches []}
               {:node/change-id "feature-2"
                :node/commit-sha "ghi789"
                :node/parents ["feature-1"]
                :node/local-branches ["feature-2"]
                :node/remote-branches []}]
              "trunk")
          path ["feature-2" "feature-1" "trunk"]
          stack (graph/path->stack g path)]
      (is (= 3 (count stack)))
      (is (= "trunk" (:change/change-id (first stack))))
      (is (= "feature-2" (:change/change-id (last stack))))
      (is (= "abc123" (:change/commit-sha (first stack)))))))
