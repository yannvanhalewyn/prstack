(ns bb-tty.prompt
  "Interactive prompts modeled on gum, see `bb-tty.inline`."
  (:refer-clojure :exclude [filter])
  (:require
    [babashka.fs :as fs]
    [babashka.process :as p]
    [bb-tty.ansi :as ansi]
    [bb-tty.fuzzy :as fuzzy]
    [bb-tty.inline :as inline]
    [bb-tty.text-area :as text-area]
    [bb-tty.text-input :as text-input]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Filter

(defn- filter-height
  "Returns the number of options shown, filling the screen below the prompt
  and input, above the help."
  [prompt rows]
  (-> (or rows (:rows inline/default-size))
    ;; Input, blank line, help and a spare line, as in gum
    (- (count (prompt-lines prompt)) 4)
    (max 1)))

(defn- filter-matches
  "Returns the options matching `query` as `{:index :indexes}`, best first.
  An empty query matches all options in their order."
  [labels query]
  (if (empty? query)
    (map (fn [i] {:index i}) (range (count labels)))
    (fuzzy/find-matches query labels)))

(defn- filter-cursor-down [{:keys [cursor offset matches] :as state} height]
  (let [n (count matches)]
    (if (zero? n)
      state
      (let [cursor (mod (inc cursor) n)]
        (assoc state
          :cursor cursor
          :offset (cond
                    ;; Wrapped around to the top
                    (< cursor offset) 0
                    (>= cursor (+ offset height)) (inc offset)
                    :else offset))))))

(defn- filter-cursor-up [{:keys [cursor offset matches] :as state} height]
  (let [n (count matches)]
    (if (zero? n)
      state
      (let [cursor (mod (dec cursor) n)]
        (assoc state
          :cursor cursor
          :offset (cond
                    (< cursor offset) (dec offset)
                    ;; Wrapped around to the bottom
                    (>= cursor (+ offset height)) (max 0 (- n height))
                    :else offset))))))

(defn- filter-update
  [{:keys [prompt options labels input matches cursor focused?] ::inline/keys [size] :as state} key]
  (let [height (filter-height prompt (:rows size))
        n (count matches)]
    (cond
      (= :enter key)
      (if (seq matches)
        (let [index (:index (nth matches cursor))]
          (inline/submit (assoc state :chosen index) (nth options index)))
        state)

      (= :esc key)
      (if focused?
        (assoc state :focused? false)
        (inline/skip state))

      (or (#{:down :ctrl-j :ctrl-n} key) (and (not focused?) (= "j" key)))
      (filter-cursor-down state height)

      (or (#{:up :ctrl-k :ctrl-p} key) (and (not focused?) (= "k" key)))
      (filter-cursor-up state height)

      focused?
      (if-let [input' (text-input/handle-key input key)]
        (cond-> (assoc state :input input')
          (not= (:value input) (:value input'))
          ;; Starts at the best match
          (assoc :matches (filter-matches labels (:value input'))
            :cursor 0
            :offset 0))
        state)

      :else
      (case key
        "/" (assoc state :focused? true)
        ("g" :home) (assoc state :cursor 0 :offset 0)
        ("G" :end) (assoc state :cursor (max 0 (dec n)) :offset (max 0 (- n height)))
        state))))

(defn- highlight
  "Styles the characters of `s` at `indexes`."
  [s indexes style]
  (->> (map-indexed vector s)
    (partition-by #(contains? indexes (first %)))
    (map (fn [chars]
           (let [text (apply str (map second chars))]
             (if (contains? indexes (ffirst chars))
               (ansi/style style text)
               text))))
    (apply str)))

(defn- filter-view
  [{:keys [prompt options labels render-option input focused? matches cursor offset]}
   {:keys [rows cols]}]
  (let [p (palette)
        height (filter-height prompt rows)
        lines (map-indexed
                (fn [i {:keys [index indexes]}]
                  (str (if (= cursor (+ offset i)) (ansi/style (:accent p) "•") " ")
                       " "
                       (if (seq indexes)
                         (highlight (nth labels index) (set indexes) (:accent p))
                         (render-option (nth options index)))))
                (->> matches (drop offset) (take height)))]
    (concat
      (map #(ansi/style (:prompt p) %) (prompt-lines prompt))
      [(text-input/view input
         {:prompt (ansi/style (:muted p) "> ")
          :placeholder "Filter..."
          :focused? focused?
          :width cols
          :cursor-style (:accent p)
          :placeholder-style (:muted p)})]
      lines
      (repeat (- height (count lines)) "")
      [""
       (help-bar (if focused?
                   [["↓↑" "navigate"] ["esc" "blur search"] ["enter" "submit"]]
                   [["↓↑" "navigate"] ["/" "search"] ["enter" "submit"]]))])))

(defn- filter-record [{:keys [prompt labels chosen]} outcome]
  (if (= :submitted outcome)
    (record-line true prompt (nth labels chosen))
    (skipped-record prompt outcome)))

(defn filter
  "Lets the user pick one of `options` by fuzzy searching them, fullscreen.
  Returns the picked option. Options are shown with `render-option`, `str` by
  default. Esc leaves the search, pressing it again returns nil. Ctrl+c
  throws, see `inline/run!`."
  [{:keys [prompt options render-option] :or {render-option str}}]
  (when (seq options)
    (let [render-option (comp str render-option)
          options (vec options)
          labels (mapv (comp ansi/strip-ansi render-option) options)]
      (inline/run!
        {:init {:prompt prompt
                :options options
                :labels labels
                :render-option render-option
                :input (text-input/init "")
                :focused? true
                :matches (filter-matches labels "")
                :cursor 0
                :offset 0}
         :update filter-update
         :view filter-view
         :record filter-record
         :tick {:ms text-input/blink-ms
                :f #(update % :input text-input/blink)}
         :fullscreen? true}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Input

(defn- input-update [{:keys [input] :as state} key]
  (case key
    :enter (inline/submit state (not-empty (str/trim (:value input))))
    :esc (inline/skip state)
    (if-let [input' (text-input/handle-key input key)]
      (assoc state :input input')
      state)))

(defn- input-view [{:keys [prompt placeholder input]} {:keys [cols]}]
  (let [p (palette)]
    (concat
      (map #(ansi/style (:prompt p) %) (prompt-lines prompt))
      [(text-input/view input
         {:prompt "> "
          :placeholder placeholder
          :focused? true
          :width cols
          :cursor-style (:accent p)
          :placeholder-style (:muted p)})
       ""
       (help-bar [["enter" "submit"]])])))

(defn- input-record [{:keys [prompt input]} outcome]
  (let [value (str/trim (:value input))]
    (cond
      (not= :submitted outcome) (skipped-record prompt outcome)
      (empty? value) (record-line false prompt "empty")
      :else (record-line true prompt value))))

(defn input
  "Prompts for a single line of text, starting with `value`. Returns the
  trimmed text, or nil when it's empty or cancelled with esc. Ctrl+c throws,
  see `inline/run!`."
  [{:keys [prompt value placeholder] :or {placeholder "Type something..."}}]
  (inline/run!
    {:init {:prompt prompt
            :placeholder placeholder
            :input (text-input/init value)}
     :update input-update
     :view input-view
     :record input-record
     :tick {:ms text-input/blink-ms
            :f #(update % :input text-input/blink)}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Write

(def ^:private write-max-height
  "Rows of the text area, as in gum."
  5)

(defn- write-options
  "Returns the options of the text area for a terminal of `size`."
  [prompt {:keys [rows cols]}]
  {:prompt (ansi/style (:text-area-prompt (palette)) "┃ ")
   :width (or cols (:cols inline/default-size))
   :height (-> (or rows (:rows inline/default-size))
             ;; Blank line and help
             (- (count (prompt-lines prompt)) 2)
             (min write-max-height)
             (max 1))})

(defn- edit-in-editor
  "Opens the text in $VISUAL or $EDITOR, like gum's ctrl+e. Keeps the text
  when the editor fails."
  [{:keys [area] :as state}]
  (let [file (fs/create-temp-file {:prefix "prstack." :suffix ".md"})
        editor (or (System/getenv "VISUAL") (System/getenv "EDITOR") "vi")]
    (try
      (spit (str file) (text-area/value area))
      (if (zero? (:exit (p/shell {:continue true} editor (str file))))
        ;; Editors end files with a newline
        (assoc state :area (text-area/init (str/replace (slurp (str file)) #"\n$" "")))
        state)
      (finally
        (fs/delete-if-exists file)))))

(defn- write-update [{:keys [prompt area] ::inline/keys [size] :as state} key]
  (case key
    :enter (inline/submit state (not-empty (str/trim (text-area/value area))))
    :esc (inline/skip state)
    :ctrl-e (inline/suspend state edit-in-editor)
    (if-let [area' (text-area/handle-key area key)]
      (assoc state :area (text-area/scroll area' (write-options prompt size)))
      state)))

(defn- write-view [{:keys [prompt placeholder area]} size]
  (let [p (palette)]
    (concat
      (map #(ansi/style (:prompt p) %) (prompt-lines prompt))
      (text-area/view area
        (assoc (write-options prompt size)
          :placeholder placeholder
          :cursor-style (:accent p)
          :placeholder-style (:muted p)))
      [""
       (help-bar [["ctrl+j" "insert newline"] ["ctrl+e" "open editor"] ["enter" "submit"]])])))

(defn- write-record [{:keys [prompt area]} outcome]
  (let [lines (str/split-lines (str/trim (text-area/value area)))]
    (cond
      (not= :submitted outcome) (skipped-record prompt outcome)
      (str/blank? (first lines)) (record-line false prompt "empty")
      :else (record-line true prompt
              (str (first lines)
                   (when (next lines)
                     (ansi/style (:muted (palette)) (format " (+%d lines)" (dec (count lines))))))))))

(defn write
  "Prompts for multiple lines of text, starting with `value`. Returns the
  trimmed text, or nil when it's empty or cancelled with esc. Enter submits,
  ctrl+j inserts a new line and ctrl+e opens the text in an editor. Ctrl+c
  throws, see `inline/run!`."
  [{:keys [prompt value placeholder] :or {placeholder "Write something..."}}]
  (inline/run!
    {:init {:prompt prompt
            :placeholder placeholder
            :area (text-area/init value)}
     :update write-update
     :view write-view
     :record write-record
     :tick {:ms text-input/blink-ms
            :f #(update % :area text-area/blink)}}))
