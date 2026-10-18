(ns bb-tty.text-area-test
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.text-area :as text-area]
    [clojure.test :refer [deftest is testing]]))

(defn- type-keys
  "Types `keys` at `row` and `col` of the text area with `value`."
  [value [row col] & keys]
  (-> (reduce text-area/handle-key
        (assoc (text-area/init value) :row row :col col)
        keys)
    (select-keys [:lines :row :col])))

(deftest test-handle-key
  (testing "starts at the end"
    (is (= {:lines ["ab" "cd"] :row 1 :col 2}
           (select-keys (text-area/init "ab\ncd") [:lines :row :col]))))
  (testing "edits within a line"
    (is (= {:lines ["aXb" "cd"] :row 0 :col 2} (type-keys "ab\ncd" [0 1] "X")))
    (is (= {:lines ["ab" ""] :row 1 :col 0} (type-keys "ab\ncd" [1 2] :ctrl-u))))
  (testing "ctrl+j splits the line"
    (is (= {:lines ["a" "b"] :row 1 :col 0} (type-keys "ab" [0 1] :ctrl-j))))
  (testing "moves between lines"
    (is (= {:lines ["abc" "d"] :row 1 :col 1} (type-keys "abc\nd" [0 3] :down)))
    (is (= {:lines ["abc" "d"] :row 0 :col 1} (type-keys "abc\nd" [1 1] :up)))
    (is (= {:lines ["abc" "d"] :row 0 :col 3} (type-keys "abc\nd" [1 0] :left)))
    (is (= {:lines ["abc" "d"] :row 1 :col 0} (type-keys "abc\nd" [0 3] :right))))
  (testing "joins lines"
    (is (= {:lines ["abcd"] :row 0 :col 2} (type-keys "ab\ncd" [1 0] :backspace)))
    (is (= {:lines ["abcd"] :row 0 :col 2} (type-keys "ab\ncd" [0 2] :delete))))
  (testing "pastes multiple lines"
    (is (= {:lines ["a1" "2" "3b"] :row 2 :col 1} (type-keys "ab" [0 1] {:paste "1\n2\n3"}))))
  (testing "returns nil for other keys"
    (is (nil? (text-area/handle-key (text-area/init "") :enter)))))

(def ^:private opts
  {:prompt "┃ " :placeholder "Write something..." :width 10 :height 3
   :cursor-style {:fg 212} :placeholder-style {:fg 240}})

(defn- view [area]
  (map ansi/strip-ansi (text-area/view area opts)))

(deftest test-view
  (testing "placeholder"
    (is (= ["┃ Write something..." "┃ " "┃ "] (view (text-area/init "")))))
  (testing "wraps lines and shows the cursor at the end"
    ;; 10 wide minus the prompt and a column for the cursor leaves 7
    (is (= ["┃ 0123456" "┃ 789 " "┃ "] (view (text-area/init "0123456789")))))
  (testing "scrolls to the cursor"
    (let [area (text-area/scroll (text-area/init "1\n2\n3\n4\n5") opts)]
      (is (= ["┃ 3" "┃ 4" "┃ 5 "] (view area))))))
