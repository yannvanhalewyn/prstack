(ns bb-tty.ansi-test
  (:require
    [bb-tty.ansi :as ansi]
    [clojure.test :refer [deftest is testing]]))

(deftest test-style
  (testing "256-color indexes"
    (is (= "\u001b[38;5;212mhi\u001b[0m" (ansi/style {:fg 212} "hi")))
    (is (= "\u001b[1;38;5;230;48;5;212mhi\u001b[0m"
           (ansi/style {:fg 230 :bg 212 :bold true} "hi"))))
  (testing "hex colors"
    (is (= "\u001b[38;2;117;113;249mhi\u001b[0m" (ansi/style {:fg "#7571F9"} "hi"))))
  (testing "no style"
    (is (= "hi" (ansi/style {} "hi"))))
  (testing "restarts the style after styled parts"
    (is (= "\u001b[38;5;99mPush \u001b[34mmain\u001b[0m\u001b[38;5;99m?\u001b[0m"
           (ansi/style {:fg 99} (str "Push " (ansi/colorize :blue "main") "?"))))))

(deftest test-palette
  (is (= {:fg 254 :bg 235} (:button-unselected (ansi/palette :dark))))
  (is (= {:fg 235 :bg 254} (:button-unselected (ansi/palette :light))))
  (is (= (:accent (ansi/palette :dark)) (:accent (ansi/palette :light)))))

(deftest test-parse-background-reply
  (testing "4 digit channels, terminated by BEL or ST"
    (is (= :dark (ansi/parse-background-reply "\u001b]11;rgb:1e1e/1e1e/2e2e\u0007")))
    (is (= :light (ansi/parse-background-reply "\u001b]11;rgb:ffff/ffff/f0f0\u001b\\"))))
  (testing "2 digit channels"
    (is (= :light (ansi/parse-background-reply "\u001b]11;rgb:fa/fa/fa\u0007"))))
  (testing "rgba"
    (is (= :dark (ansi/parse-background-reply "\u001b]11;rgba:0000/0000/0000/ffff\u0007"))))
  (testing "followed by the DA1 reply"
    (is (= :dark (ansi/parse-background-reply "\u001b]11;rgb:0000/0000/0000\u0007\u001b[?62;22c"))))
  (testing "no OSC 11 support, only the DA1 reply"
    (is (nil? (ansi/parse-background-reply "\u001b[?62;22c"))))
  (testing "no reply"
    (is (nil? (ansi/parse-background-reply "")))))

(deftest test-parse-colorfgbg
  (is (= :dark (ansi/parse-colorfgbg "15;0")))
  (is (= :dark (ansi/parse-colorfgbg "15;default;8")))
  (is (= :light (ansi/parse-colorfgbg "0;15")))
  (is (= :light (ansi/parse-colorfgbg "0;7")))
  (is (nil? (ansi/parse-colorfgbg nil)))
  (is (nil? (ansi/parse-colorfgbg "default;default"))))
