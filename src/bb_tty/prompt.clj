(ns bb-tty.prompt
  "Interactive prompts modeled on gum, see `bb-tty.inline`."
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.inline :as inline]
    [bb-tty.tty :as tty]
    [clojure.string :as str])
  (:import
    (java.util.concurrent ExecutionException)))

(defn- palette []
  (ansi/palette (tty/background)))

(defn- help-bar
  "Renders key bindings, a list of `[key description]`, like gum's help line."
  [bindings]
  (let [p (palette)]
    (str/join (ansi/style (:help-separator p) " • ")
      (for [[k desc] bindings]
        (str (ansi/style (:help-key p) k) " " (ansi/style (:help-desc p) desc))))))

(defn- prompt-lines [prompt]
  (if prompt (str/split-lines prompt) []))

(defn- record-line
  "Renders the line that remains once a prompt finished. Only the first line
  of `prompt` is shown. When `ok?` the answer is highlighted, otherwise it's
  dimmed, e.g. for a cancelled prompt."
  [ok? prompt answer]
  (let [p (palette)
        style (if ok? (:accent p) (:muted p))]
    (->> [(ansi/style style (if ok? "✔" "✗"))
          (some-> (first (prompt-lines prompt)) str/triml)
          (ansi/style style answer)]
      (remove str/blank?)
      (str/join " "))))

(defn- skipped-record
  "The record of a prompt that was cancelled with esc or ctrl+c."
  [prompt outcome]
  (record-line false prompt (if (= :aborted outcome) "aborted" "skipped")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Spinner

(def ^:private spinner
  "Inspired by gum's default `dot` spinner. The frames have a trailing space,
  as in gum."
  {:frames ["⣾ " "⣽ " "⣻ " "⢿ " "⡿ " "⣟ " "⣯ " "⣷ "]
   :ms 100})

(def ^:private spinner-poll-ms
  "How often to check if the spinner's work is done."
  20)

(defn spin
  "Calls `f` in the background while displaying a spinner with a title,
  returning the result of `f`. Without a terminal, `f` is simply called.
  Pressing ctrl+c cancels `f` and throws, see `inline/run!`."
  [f {:keys [title]}]
  (if-not (System/console)
    (f)
    (let [task (future (f))
          started (System/currentTimeMillis)
          {:keys [frames ms]} spinner]
      (try
        (inline/run!
          {:init {:frame 0}
           ;; Only ctrl+c does something, which `inline/run!` handles
           :update (fn [state _key] state)
           :tick {:ms spinner-poll-ms
                  :f (fn [state]
                       (if (realized? task)
                         (inline/submit state nil)
                         (assoc state :frame
                           (quot (- (System/currentTimeMillis) started) ms))))}
           :view (fn [{:keys [frame]} _size]
                   [(str (ansi/style (:accent (palette))
                           (nth frames (mod frame (count frames))))
                         title)])})
        (catch Exception e
          (future-cancel task)
          (throw e)))
      (try
        @task
        (catch ExecutionException e
          (throw (or (ex-cause e) e)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Confirm

(defn- confirm-answer [state yes?]
  (inline/submit (assoc state :yes? yes?) yes?))

(defn- confirm-update [state key]
  (case key
    ("y" "Y") (confirm-answer state true)
    ("n" "N" "q" :esc) (confirm-answer state false)
    (:left :right "h" "l" :tab :shift-tab :ctrl-n :ctrl-p) (update state :yes? not)
    :enter (confirm-answer state (:yes? state))
    state))

(defn- confirm-view [{:keys [prompt yes?]} _size]
  (let [p (palette)
        button (fn [label selected?]
                 (str " "
                      (ansi/style (if selected? (:button-selected p) (:button-unselected p))
                        (str "   " label "   "))
                      " "))]
    (concat
      (for [line (prompt-lines prompt)]
        (str " " (ansi/style (:prompt p) line)))
      [""
       (str (button "Yes" yes?) (button "No" (not yes?)))
       ""
       (help-bar [["←→" "toggle"] ["enter" "submit"] ["y" "Yes"] ["n" "No"]])])))

(defn- confirm-record [{:keys [prompt yes?]} outcome]
  (if (= :aborted outcome)
    (skipped-record prompt outcome)
    (record-line yes? prompt (if yes? "Yes" "No"))))

(defn confirm
  "Asks a yes or no question, returning true for yes. Starts on yes, unless
  `:default` is false. Esc answers no, ctrl+c throws, see `inline/run!`."
  [{:keys [prompt default] :or {default true}}]
  (inline/run!
    {:init {:prompt prompt :yes? default}
     :update confirm-update
     :view confirm-view
     :record confirm-record}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Choose

(def ^:private choose-max-page-size
  "Options per page, as in gum."
  10)

(defn- choose-page-size
  "Returns the number of options per page, fewer than gum's 10 when the
  terminal is too small to show them with the prompt and help."
  [prompt rows]
  (-> (or rows (:rows inline/default-size))
    ;; Blank lines, page dots and help
    (- (count (prompt-lines prompt)) 4)
    (min choose-max-page-size)
    (max 1)))

(defn- choose-update [{:keys [prompt options index] ::inline/keys [size] :as state} key]
  (let [n (count options)
        page-size (choose-page-size prompt (:rows size))]
    (case key
      ("j" :down :ctrl-j :ctrl-n) (assoc state :index (mod (inc index) n))
      ("k" :up :ctrl-k :ctrl-p) (assoc state :index (mod (dec index) n))
      ("l" :right :ctrl-f) (assoc state :index (min (+ index page-size) (dec n)))
      ("h" :left :ctrl-b) (assoc state :index (max (- index page-size) 0))
      ("g" :home) (assoc state :index 0)
      ("G" :end) (assoc state :index (dec n))
      :enter (inline/submit state (nth options index))
      :esc (inline/skip state)
      state)))

(defn- choose-view [{:keys [prompt options index render-option]} {:keys [rows]}]
  (let [p (palette)
        page-size (choose-page-size prompt rows)
        page (quot index page-size)
        pages (quot (+ (count options) page-size -1) page-size)
        start (* page page-size)
        visible (->> options (drop start) (take page-size))]
    (concat
      (map #(ansi/style (:prompt p) %) (prompt-lines prompt))
      (map-indexed
        (fn [i option]
          (if (= index (+ start i))
            (ansi/style (:accent p) (str "> " (render-option option)))
            (str "  " (render-option option))))
        visible)
      (if (> pages 1)
        ;; Pads the last page so the dots stay in place
        (concat (repeat (- page-size (count visible)) "")
          [""
           (str "  " (str/join (for [i (range pages)]
                                 (ansi/style (if (= i page) (:dot-active p) (:dot-inactive p))
                                   "•"))))])
        ;; gum renders a blank line after the options
        [""])
      [""
       (help-bar [["←↓↑→" "navigate"] ["enter" "submit"]])])))

(defn- choose-record [{:keys [prompt options index render-option]} outcome]
  (if (= :submitted outcome)
    (record-line true prompt (render-option (nth options index)))
    (skipped-record prompt outcome)))

(defn choose
  "Lets the user pick one of `options`, returning it. Options are shown with
  `render-option`, `str` by default. Returns nil when cancelled with esc,
  ctrl+c throws, see `inline/run!`."
  [{:keys [prompt options render-option] :or {render-option str}}]
  (when (seq options)
    (inline/run!
      {:init {:prompt prompt
              :options (vec options)
              :index 0
              :render-option (comp str render-option)}
       :update choose-update
       :view choose-view
       :record choose-record})))
