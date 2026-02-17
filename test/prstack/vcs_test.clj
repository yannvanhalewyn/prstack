(ns prstack.vcs-test
  "Integration tests for VCS implementations.

  These tests create actual test repositories and verify that VCS operations
  work correctly with the project-dir option."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [prstack.system :as system]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]
    [prstack.vcs.graph :as graph]
    [prstack.vcs.jujutsu :as vcs.jj]))

(def ^:dynamic *test-repo-dir* nil)

(defn- create-test-repo!
  "Creates a test repository with a branch structure similar to mktestrepo.
  Returns the path to the test repo.

  Structure:
    main (trunk)
      └── feature-base
            ├── feature-step-1
            │     └── feature-step-2
            └── feature-docs"
  [base-dir]
  (let [test-repo-dir (str base-dir "/test-repo-" (System/currentTimeMillis))]
    ;; Create directory
    (u/run-cmd ["mkdir" "-p" test-repo-dir])

    ;; Initialize repo
    (u/run-cmd ["touch" "README.md"] {:dir test-repo-dir})
    (u/run-cmd ["mkdir" "src"] {:dir test-repo-dir})
    (u/run-cmd ["touch" "src/feature.js"] {:dir test-repo-dir})
    (u/run-cmd ["mkdir" "docs"] {:dir test-repo-dir})
    (u/run-cmd ["touch" "docs/doc.md"] {:dir test-repo-dir})
    (u/run-cmd ["jj" "git" "init" "--colocate"] {:dir test-repo-dir})
    (u/run-cmd ["jj" "describe" "-m" "Initial Commit"] {:dir test-repo-dir})
    (u/run-cmd ["jj" "bookmark" "set" "main"] {:dir test-repo-dir})
    (u/run-cmd ["jj" "new"] {:dir test-repo-dir})

    ;; Create feature-base
    (u/run-cmd ["jj" "bookmark" "set" "feature-base"] {:dir test-repo-dir})
    (u/run-cmd ["jj" "new"] {:dir test-repo-dir})

    ;; Create feature-step-1
    (u/run-cmd ["sh" "-c" "echo \"var a = 'feature step 1'\" >> src/feature.js"] {:dir test-repo-dir})
    (u/run-cmd ["jj" "describe" "-m" "Add feature"] {:dir test-repo-dir})
    (u/run-cmd ["jj" "bookmark" "set" "feature-step-1"] {:dir test-repo-dir})
    (u/run-cmd ["jj" "new"] {:dir test-repo-dir})

    ;; Create feature-step-2
    (u/run-cmd ["sh" "-c" "echo \"var b = 'feature step 2'\" >> src/feature.js"] {:dir test-repo-dir})
    (u/run-cmd ["jj" "describe" "-m" "Add feature step 2"] {:dir test-repo-dir})
    (u/run-cmd ["jj" "bookmark" "set" "feature-step-2"] {:dir test-repo-dir})

    ;; Create feature-docs branch (parallel to feature-step-1)
    (u/run-cmd ["jj" "new" "feature-base"] {:dir test-repo-dir})
    (u/run-cmd ["sh" "-c" "echo \"Some documentation\" >> docs/doc.md"] {:dir test-repo-dir})
    (u/run-cmd ["jj" "describe" "-m" "Add docs"] {:dir test-repo-dir})
    (u/run-cmd ["jj" "bookmark" "set" "feature-docs"] {:dir test-repo-dir})

    test-repo-dir))

(defn- cleanup-test-repo! [test-repo-dir]
  (when test-repo-dir
    (u/run-cmd ["rm" "-rf" test-repo-dir])))

(defn test-repo-fixture [f]
  (let [test-repo-dir (create-test-repo! "./tmp")]
    (try
      (binding [*test-repo-dir* test-repo-dir]
        (f))
      (finally
        (cleanup-test-repo! test-repo-dir)))))

(use-fixtures :each test-repo-fixture)

;; ============================================================================
;; Jujutsu VCS Tests
;; ============================================================================

(deftest jujutsu-read-commit-log-test
  (testing "reads all nodes from a jujutsu repository using project-dir"
    (let [vcs (system/new-vcs
                ;; user config
                {:vcs :jujutsu}
                ;; global opts (--project-dir)
                {:project-dir *test-repo-dir*})
          g (vcs/read-graph vcs {})]

      (is (some? (:graph/trunk-id g)) "Should have a trunk change id")
      (is (seq (vals (:graph/nodes g))) "Should have nodes")

      ;; Check that we have the expected bookmarks
      (let [all-branchnames (mapcat :change/local-branchnames (vals (:graph/nodes g)))]
        (is (some #{"main"} all-branchnames) "Should have main branch")
        (is (some #{"feature-base"} all-branchnames) "Should have feature-base branch")
        (is (some #{"feature-step-1"} all-branchnames) "Should have feature-step-1 branch")
        (is (some #{"feature-step-2"} all-branchnames) "Should have feature-step-2 branch")
        (is (some #{"feature-docs"} all-branchnames) "Should have feature-docs branch")))))

(deftest jujutsu-build-graph-test
  (testing "builds a graph from jujutsu nodes"
    (let [vcs (system/new-vcs
                {:vcs :jujutsu}
                {:project-dir *test-repo-dir*})
          g (vcs/read-graph vcs {})]

      (is (some? (:graph/trunk-id g)) "Graph trunk should exist")
      (is (map? (:graph/nodes g)) "Should have nodes map")

      ;; Find the trunk node and verify it has children
      (let [trunk-node (graph/get-node g (:graph/trunk-id g))]
        (is (some? trunk-node) "Should find trunk node")
        (is (seq (:change/children-ids trunk-node)) "Trunk should have children")))))

(deftest jujutsu-graph-traversal-test
  (testing "can traverse from leaf to trunk"
    (let [vcs (system/new-vcs
                {:vcs :jujutsu}
                {:project-dir *test-repo-dir*})
          g (vcs/read-graph vcs {})

          ;; Find feature-step-2 node
          step2-node (->> (graph/all-nodes g)
                       (filter #(some #{"feature-step-2"} (:change/local-branchnames %)))
                       first)]

      (is (some? step2-node) "Should find feature-step-2 node")

      (let [paths (graph/find-all-paths-to-trunk g (:change/change-id step2-node))]
        (is (= 1 (count paths)) "Should have exactly one path to trunk")
        (is (= (:graph/trunk-id g) (last (first paths))) "Path should end at trunk")
        ;; Path should go through: feature-step-2 -> feature-step-1 -> feature-base -> main
        (is (>= (count (first paths)) 4) "Path should have at least 4 nodes")))))

(deftest jujutsu-current-change-id-test
  (testing "gets current change id using project-dir"
    (let [vcs (system/new-vcs
                {:vcs :jujutsu}
                {:project-dir *test-repo-dir*})
          change-id (vcs/current-change-id vcs)]
      (is (string? change-id) "Should return a string")
      (is (not-empty change-id) "Should not be empty"))))

(deftest jujutsu-detect-trunk-branch-test
  (testing "detects trunk branch using project-dir"
    (let [trunk (vcs.jj/detect-trunk-branch {:dir *test-repo-dir*})]
      (is (= "main" trunk) "Should detect main as trunk branch"))))
