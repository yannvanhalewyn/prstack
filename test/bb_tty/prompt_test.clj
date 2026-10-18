(ns bb-tty.prompt-test
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.inline :as inline]
    [bb-tty.prompt :as prompt]
    [bb-tty.text-input :as text-input]
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

(defn- choose-state [n & {:as opts}]
  (merge {:prompt "Pick one"
          :options (mapv #(str "opt " %) (range n))
          :index 0
          :render-option str}
    opts))

(deftest test-choose-update
  (let [press (partial press #'prompt/choose-update)]
    (testing "moves the cursor, wrapping around"
      (is (= 1 (:index (press (choose-state 3) "j"))))
      (is (= 2 (:index (press (choose-state 3) :up))))
      (is (= 0 (:index (press (choose-state 3) :down :ctrl-n "j")))))
    (testing "jumps to the first and last option"
      (is (= 24 (:index (press (choose-state 25) "G"))))
      (is (= 0 (:index (press (choose-state 25) :end "g")))))
    (testing "flips pages of 10 options"
      (is (= 10 (:index (press (choose-state 25) "l"))))
      (is (= 24 (:index (press (choose-state 25) "l" :right :right))))
      (is (= 3 (:index (press (choose-state 25 :index 13) :left))))
      (is (= 0 (:index (press (choose-state 25 :index 3) "h")))))
    (testing "pages are smaller on a small terminal"
      (is (= 5 (:index (press (choose-state 25 ::inline/size {:rows 10 :cols 80}) "l")))))
    (testing "enter submits the option under the cursor"
      (is (= "opt 1" (answer (press (choose-state 3) "j" :enter)))))
    (testing "esc skips"
      (is (= :skipped (::inline/outcome (press (choose-state 3) :esc)))))))

(defn- choose-lines [state]
  (map ansi/strip-ansi (#'prompt/choose-view state {:rows 24 :cols 80})))

(deftest test-choose-view
  (testing "a single page"
    (is (= ["Pick one"
            "> opt 0"
            "  opt 1"
            ""
            ""
            "←↓↑→ navigate • enter submit"]
           (choose-lines (choose-state 2)))))
  (testing "the last page is padded to keep the dots in place"
    (is (= (concat ["Pick one"
                    "  opt 10"
                    "> opt 11"]
             (repeat 8 "")
             [""
              "  ••"
              ""
              "←↓↑→ navigate • enter submit"])
           (choose-lines (choose-state 12 :index 11)))))
  (testing "renders options with render-option"
    (is (= "> Git"
           (second (choose-lines (choose-state 0 :options [{:label "Git"}] :render-option :label)))))))

(deftest test-choose-record
  (let [record (comp ansi/strip-ansi #'prompt/choose-record)]
    (is (= "✔ Pick one opt 1" (record (choose-state 3 :index 1) :submitted)))
    (is (= "✗ Pick one skipped" (record (choose-state 3) :skipped)))
    (is (= "✗ Pick one aborted" (record (choose-state 3) :aborted)))))

(defn- filter-state [options & {:as opts}]
  (merge {:prompt "Pick a branch"
          :options options
          :labels options
          :render-option str
          :input (text-input/init "")
          :focused? true
          :matches (#'prompt/filter-matches options "")
          :cursor 0
          :offset 0
          ::inline/size {:rows 10 :cols 80}}
    opts))

(def ^:private branches ["main" "feature-base-api" "feat/login" "my-feature" "trunk"])

(deftest test-filter-update
  (let [press (partial press #'prompt/filter-update)
        labels (fn [state] (map #(nth branches (:index %)) (:matches state)))]
    (testing "typing filters and ranks the options"
      (is (= ["feat/login" "my-feature" "feature-base-api"]
             (labels (press (filter-state branches) "f" "e" "a" "t")))))
    (testing "j and k are typed while searching, navigate otherwise"
      (is (= "j" (get-in (press (filter-state branches) "j") [:input :value])))
      (is (= 1 (:cursor (press (filter-state branches) :esc "j")))))
    (testing "enter submits the option under the cursor"
      (is (= "feat/login" (answer (press (filter-state branches) "l" "o" "g" :enter))))
      (is (= "feature-base-api" (answer (press (filter-state branches) :down :enter)))))
    (testing "enter does nothing without matches"
      (is (nil? (::inline/outcome (press (filter-state branches) "z" "z" :enter)))))
    (testing "esc leaves the search, then skips"
      (is (false? (:focused? (press (filter-state branches) :esc))))
      (is (= :skipped (::inline/outcome (press (filter-state branches) :esc :esc))))
      (is (true? (:focused? (press (filter-state branches) :esc "/")))))
    (testing "scrolls to keep the cursor in view"
      (let [many (mapv #(str "branch-" %) (range 20))
            ;; 10 rows minus prompt, input, blank, help and spare leave 5
            state (press (filter-state many) :down :down :down :down :down)]
        (is (= [5 1] [(:cursor state) (:offset state)]))
        (is (= [19 15] ((juxt :cursor :offset) (press (filter-state many) :up))))
        (is (= [0 0] ((juxt :cursor :offset) (press (filter-state many) :up :down))))))))

(deftest test-filter-view
  (is (= ["Pick a branch"
          "> feat "
          "• feat/login"
          "  my-feature"
          "  feature-base-api"
          ""
          ""
          ""
          "↓↑ navigate • esc blur search • enter submit"]
         (map ansi/strip-ansi
           (#'prompt/filter-view
             (press #'prompt/filter-update (filter-state branches) "f" "e" "a" "t")
             {:rows 10 :cols 80})))))

(deftest test-filter-record
  (let [record (comp ansi/strip-ansi #'prompt/filter-record)]
    (is (= "✔ Pick a branch feat/login"
           (record (filter-state branches :chosen 2) :submitted)))
    (is (= "✗ Pick a branch skipped" (record (filter-state branches) :skipped)))))

(defn- input-state [value]
  {:prompt "PR title"
   :placeholder "Type something..."
   :input (text-input/init value)})

(deftest test-input-update
  (let [press (partial press #'prompt/input-update)]
    (testing "edits the value"
      (is (= "feat: login" (answer (press (input-state "feat") ":" " " "l" "o" "g" "i" "n" :enter)))))
    (testing "returns the trimmed value, nil when empty"
      (is (= "fix" (answer (press (input-state "  fix  ") :enter))))
      (is (nil? (answer (press (input-state "abc") :ctrl-u :enter)))))
    (testing "esc skips"
      (is (= :skipped (::inline/outcome (press (input-state "abc") :esc)))))))

(deftest test-input-view
  (is (= ["PR title" "> feat " "" "enter submit"]
         (map ansi/strip-ansi (#'prompt/input-view (input-state "feat") {:rows 24 :cols 80}))))
  (is (= "> Type something..."
         (ansi/strip-ansi (second (#'prompt/input-view (input-state "") {:rows 24 :cols 80}))))))

(deftest test-input-record
  (let [record (comp ansi/strip-ansi #'prompt/input-record)]
    (is (= "✔ PR title feat: login" (record (input-state "feat: login ") :submitted)))
    (is (= "✗ PR title empty" (record (input-state " ") :submitted)))
    (is (= "✗ PR title skipped" (record (input-state "x") :skipped)))))
