(ns bb-tty.fuzzy-test
  (:require
    [bb-tty.fuzzy :as fuzzy]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is testing]]))

(deftest test-match
  (testing "matches characters in order, ignoring case"
    (is (= [0 4] (:indexes (fuzzy/match "ml" "mainLine"))))
    (is (nil? (fuzzy/match "lm" "mainline"))))
  (testing "picks the best position rather than the first"
    (is (= [0 10] (:indexes (fuzzy/match "tk" "The Black Knight")))))
  (testing "an empty pattern matches nothing"
    (is (nil? (fuzzy/match "" "main")))))

(deftest test-find-matches-like-sahilm-fuzzy
  (let [{:keys [strings results]}
        (edn/read-string (slurp (io/file "test/bb_tty/fuzzy_reference.edn")))]
    (doseq [[pattern expected] results]
      (testing pattern
        (is (= expected
               (map #(select-keys % [:index :score :indexes])
                 (fuzzy/find-matches pattern strings))))))))
