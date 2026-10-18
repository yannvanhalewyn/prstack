(ns bb-tty.text-input-test
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.text-input :as text-input]
    [clojure.test :refer [deftest is testing]]))

(defn- type-keys [value pos & keys]
  (-> (reduce text-input/handle-key (assoc (text-input/init value) :pos pos) keys)
    (select-keys [:value :pos])))

(deftest test-handle-key
  (testing "inserts at the cursor"
    (is (= {:value "abXc" :pos 3} (type-keys "abc" 2 "X"))))
  (testing "moves by character and word"
    (is (= 1 (:pos (type-keys "abc" 2 :left))))
    (is (= 7 (:pos (type-keys "foo bar baz" 11 :alt-b :alt-b :alt-f))))
    (is (= 0 (:pos (type-keys "foo" 3 :ctrl-a))))
    (is (= 3 (:pos (type-keys "foo" 0 :end)))))
  (testing "deletes"
    (is (= {:value "ac" :pos 1} (type-keys "abc" 2 :backspace)))
    (is (= {:value "abc" :pos 0} (type-keys "abc" 0 :backspace)))
    (is (= {:value "ac" :pos 1} (type-keys "abc" 1 :ctrl-d)))
    (is (= {:value "foo  baz" :pos 4} (type-keys "foo bar baz" 7 :ctrl-w)))
    (is (= {:value "foo  baz" :pos 4} (type-keys "foo bar baz" 4 :alt-d)))
    (is (= {:value "bar" :pos 0} (type-keys "foobar" 3 :ctrl-u)))
    (is (= {:value "foo" :pos 3} (type-keys "foobar" 3 :ctrl-k))))
  (testing "returns nil for other keys"
    (is (nil? (text-input/handle-key (text-input/init "") :enter)))
    (is (nil? (text-input/handle-key (text-input/init "") :up)))))

(deftest test-view
  (let [opts {:prompt "> " :placeholder "Filter..." :focused? true :width 80
              :cursor-style {:fg 212} :placeholder-style {:fg 240}}
        view (fn [input opts] (ansi/strip-ansi (text-input/view input opts)))]
    (is (= "> Filter..." (view (text-input/init "") opts)))
    (is (= "> abc " (view (text-input/init "abc") opts)))
    (testing "the cursor is the reversed character under it"
      (is (re-find #"\u001b\[7;38;5;212mb"
            (text-input/view (assoc (text-input/init "abc") :pos 1) opts))))
    (testing "scrolls to keep the cursor in view"
      (is (= "> 6789 " (view (text-input/init "0123456789") (assoc opts :width 8)))))))
