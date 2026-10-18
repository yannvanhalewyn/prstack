(ns bb-tty.inline-test
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.inline :as inline]
    [clojure.test :refer [deftest is testing]]))

(deftest test-frame-rows
  (testing "one row per line that fits"
    (is (= 3 (inline/frame-rows ["a" "" "abc"] 10))))
  (testing "lines wrap when the terminal got narrower"
    (is (= 4 (inline/frame-rows ["0123456789" "x"] 4))))
  (testing "ANSI codes take no space"
    (is (= 1 (inline/frame-rows [(ansi/style {:fg 212} "abcd")] 4)))))

(deftest test-fit-lines
  (testing "truncates one column short of the width"
    (is (= ["abc" "de"] (inline/fit-lines ["abcdef" "de"] {:rows 10 :cols 4}))))
  (testing "caps the lines to the height"
    (is (= ["a" "b"] (inline/fit-lines ["a" "b" "c"] {:rows 2 :cols 80})))))
