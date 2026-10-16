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
          g (graph/build-graph nodes "trunk" {})]
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
          g (graph/build-graph nodes "trunk" {})]
      (is (= :trunk (get-in g [:graph/nodes "trunk" :change/type]))))))

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
              "trunk"
              {})
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
              "trunk"
              {})
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
              "trunk"
              {})
          paths (graph/find-all-paths-to-trunk g "merge")]
      (is (= [["merge" "branch-a" "trunk"]
              ["merge" "branch-b" "trunk"]]
            paths)
        "Paths through the first parent come first"))))

(deftest test-find-all-paths-in-deep-history
  (testing "doesn't overflow the stack on long histories, even off the main thread"
    ;; Background threads have a much smaller stack than the main thread, and
    ;; prstack reads workspace repositories in parallel.
    (let [n 5000
          g (graph/build-graph
              (for [i (range (inc n))]
                {:change/change-id (str i)
                 :change/parent-ids (if (zero? i) [] [(str (dec i))])
                 :change/local-branchnames (if (zero? i) ["main"] [])
                 :change/remote-branchnames []})
              "0"
              {})
          paths @(future (graph/find-all-paths-to-trunk g (str n)))]
      (is (= 1 (count paths)))
      (is (= (inc n) (count (first paths)))))))

(deftest test-bookmarks-subgraph-with-many-merges
  (testing "doesn't blow up on histories with many merges"
    ;; A chain of diamonds: every merge joins two commits forked from the
    ;; previous merge. Removing unbookmarked commits used to duplicate edges at
    ;; every merge, overflowing the stack once realized.
    (let [n 40
          nodes (concat
                  [{:change/change-id "trunk"
                    :change/parent-ids []
                    :change/local-branchnames ["main"]
                    :change/remote-branchnames []}]
                  (mapcat
                    (fn [i]
                      (let [prev (if (zero? i) "trunk" (str "m" (dec i)))]
                        [{:change/change-id (str "a" i)
                          :change/parent-ids [prev]
                          :change/local-branchnames []
                          :change/remote-branchnames []}
                         {:change/change-id (str "b" i)
                          :change/parent-ids [prev]
                          :change/local-branchnames []
                          :change/remote-branchnames []}
                         {:change/change-id (str "m" i)
                          :change/parent-ids [(str "a" i) (str "b" i)]
                          :change/local-branchnames []
                          :change/remote-branchnames []}]))
                    (range n))
                  [{:change/change-id "feature"
                    :change/parent-ids [(str "m" (dec n))]
                    :change/local-branchnames ["feature"]
                    :change/remote-branchnames []}])
          g (graph/bookmarks-subgraph (graph/build-graph nodes "trunk" {}))]
      (is (= ["trunk"] (:change/parent-ids (graph/get-node g "feature"))))
      (is (= ["feature"] (:change/children-ids (graph/get-node g "trunk")))))))

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
              "new-trunk-2"
              {})
          ;; Find path from feature-2 to the actual fork point (old-trunk)
          paths (graph/find-all-paths-to-trunk g "feature-2" "old-trunk")]
      (is (= 1 (count paths)))
      (is (= ["feature-2" "feature-1" "old-trunk"] (first paths))))))
