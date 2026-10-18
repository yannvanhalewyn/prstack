(ns bb-tty.prompt-test
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.inline :as inline]
    [bb-tty.prompt :as prompt]
    [clojure.test :refer [deftest is testing]]))

(defn- press
  "Feeds `keys` to the update function of a prompt."
  [update-fn state & keys]
  (reduce update-fn state keys))

(defn- answer [state]
  (when (= :submitted (::inline/outcome state))
    (::inline/value state)))

(deftest test-confirm-update
  (let [press (partial press #'prompt/confirm-update)
        init {:prompt "Push?" :yes? true}]
    (testing "y and n answer right away"
      (is (true? (answer (press init "y"))))
      (is (false? (answer (press init "n"))))
      (is (false? (answer (press init "q")))))
    (testing "esc answers no"
      (is (false? (answer (press init :esc)))))
    (testing "enter submits the selected button"
      (is (true? (answer (press init :enter))))
      (is (false? (answer (press init :tab :enter))))
      (is (true? (answer (press init :left "l" :enter)))))
    (testing "other keys do nothing"
      (is (= init (press init "x" :up))))))

(deftest test-confirm-view
  (is (= [" Merge #1?"
          " This will merge feat onto main."
          ""
          "    Yes        No    "
          ""
          "←→ toggle • enter submit • y Yes • n No"]
         (map ansi/strip-ansi
           (#'prompt/confirm-view
             {:prompt "Merge #1?\nThis will merge feat onto main." :yes? true}
             {:rows 24 :cols 80})))))

(deftest test-confirm-record
  (let [record (comp ansi/strip-ansi #'prompt/confirm-record)]
    (is (= "✔ Merge #1? Yes" (record {:prompt "Merge #1?\nmore" :yes? true} :submitted)))
    (is (= "✗ Create PRs? No" (record {:prompt "  Create PRs?" :yes? false} :submitted)))
    (is (= "✗ Push? aborted" (record {:prompt "Push?" :yes? true} :aborted)))))
