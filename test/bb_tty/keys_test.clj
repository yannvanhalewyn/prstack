(ns bb-tty.keys-test
  (:require
    [bb-tty.keys :as keys]
    [clojure.test :refer [deftest is testing]]))

(defn- bytes-of
  "Returns the bytes of `s` as unsigned values, as read from stdin."
  [s]
  (mapv #(bit-and % 0xff) (.getBytes ^String s "UTF-8")))

(defn- parse-all
  "Parses all keys from `s`, as if no more input follows."
  [s]
  (loop [bytes (bytes-of s)
         keys []]
    (if (empty? bytes)
      keys
      (let [[key remaining] (keys/parse bytes true)]
        (recur remaining (conj keys key))))))

(deftest test-parse
  (testing "characters"
    (is (= ["j" "K" " " "é" "→"] (parse-all "jK é→"))))
  (testing "control keys"
    (is (= [:enter :tab :backspace :backspace :ctrl-c :ctrl-j :ctrl-a]
           (parse-all "\r\t\u007f\b\u0003\n\u0001"))))
  (testing "escape sequences"
    (is (= [:up :down :right :left :up] (parse-all "\u001b[A\u001b[B\u001b[C\u001b[D\u001bOA")))
    (is (= [:home :end :delete :shift-tab :alt-left :ctrl-right]
           (parse-all "\u001b[H\u001b[4~\u001b[3~\u001b[Z\u001b[1;3D\u001b[1;5C"))))
  (testing "alt keys"
    (is (= [:alt-b :alt-backspace :alt-left] (parse-all "\u001bb\u001b\u007f\u001b\u001b[D"))))
  (testing "a lone escape"
    (is (= [:esc] (parse-all "\u001b")))
    (is (= [:esc :esc] (parse-all "\u001b\u001b")))
    (is (= [:esc "1"] (parse-all "\u001b1"))))
  (testing "unknown sequences are skipped as a whole"
    (is (= [:unknown "x"] (parse-all "\u001b[15~x")))
    (is (= [:unknown :unknown "x"] (parse-all "\u001b]11;rgb:0/0/0\u0007\u001b[?62;22cx"))))
  (testing "bracketed paste"
    (is (= [{:paste "line 1\nline 2"} "x"]
           (parse-all "\u001b[200~line 1\r\nline 2\u001b[201~x")))))

(deftest test-parse-incomplete
  (testing "waits for the rest of a sequence"
    (is (nil? (keys/parse (bytes-of "\u001b") false)))
    (is (nil? (keys/parse (bytes-of "\u001b[1;3") false)))
    (is (nil? (keys/parse (bytes-of "\u001b[200~half") false)))
    (is (nil? (keys/parse (take 1 (bytes-of "é")) false))))
  (testing "returns the rest of the bytes"
    (is (= [:up (bytes-of "jk")] (keys/parse (bytes-of "\u001b[Ajk") false)))))
