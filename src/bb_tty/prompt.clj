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

(defn- record-line
  "Renders the line that remains once a prompt finished. Only the first line
  of `prompt` is shown. When `ok?` the answer is highlighted, otherwise it's
  dimmed, e.g. for a cancelled prompt."
  [ok? prompt answer]
  (let [p (palette)
        style (if ok? (:accent p) (:muted p))]
    (str (ansi/style style (if ok? "✔" "✗")) " "
         (str/triml (first (str/split-lines prompt))) " "
         (ansi/style style answer))))

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
      (for [line (str/split-lines prompt)]
        (str " " (ansi/style (:prompt p) line)))
      [""
       (str (button "Yes" yes?) (button "No" (not yes?)))
       ""
       (help-bar [["←→" "toggle"] ["enter" "submit"] ["y" "Yes"] ["n" "No"]])])))

(defn- confirm-record [{:keys [prompt yes?]} outcome]
  (if (= :aborted outcome)
    (record-line false prompt "aborted")
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
