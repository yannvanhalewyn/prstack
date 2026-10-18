(ns bb-tty.inline
  "Runs interactive prompts below the cursor, repainting them in place, modeled
  on gum and bubbletea's inline renderer. A prompt is a state, an update
  function receiving keys and a view function returning lines. Once finished,
  the prompt is replaced with a single line recording the outcome."
  (:refer-clojure :exclude [run!])
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [clojure.string :as str]))

(defn submit
  "Finishes the prompt, returning `value`."
  [state value]
  (assoc state ::outcome :submitted ::value value))

(defn skip
  "Finishes the prompt without a value."
  [state]
  (assoc state ::outcome :skipped))

(defn interrupted?
  "Returns true when `e` was thrown because ctrl+c was pressed in a prompt."
  [e]
  (= ::interrupted (:type (ex-data e))))

(defn- abort [state]
  (assoc state ::outcome :aborted))

(defn frame-rows
  "Returns the number of rows `lines` take up on a terminal `cols` wide. Lines
  wrap when the terminal got narrower since they were printed."
  [lines cols]
  (reduce + (for [line lines]
              (max 1 (long (Math/ceil (/ (ansi/visual-length line) (double cols))))))))

(defn fit-lines
  "Fits `lines` on a terminal of `size`. Lines are truncated one column short
  of the width, as writing up to the last column can wrap the line."
  [lines {:keys [rows cols]}]
  (->> lines
    (take rows)
    (mapv #(ansi/truncate % (dec cols)))))

(defn- erase
  "Moves to the first line of `frame` and clears it and everything below."
  [frame cols]
  (if (seq (:lines frame))
    (str (ansi/cursor-up (dec (frame-rows (:lines frame) cols)))
         "\r" ansi/CLEAR_TO_END)
    ""))

(defn- paint [frame lines {:keys [cols]} fullscreen?]
  (str ansi/SYNC_START
       (if fullscreen?
         ;; Overwrites line by line instead of clearing the screen to avoid
         ;; flickering. No newline after the last line, on the last row it
         ;; would scroll the screen.
         (str ansi/CURSOR_HOME
              (str/join "\r\n" (map #(str % ansi/CLEAR_TO_EOL) lines))
              ansi/CLEAR_TO_END)
         (str (erase frame cols)
              (str/join "\r\n" lines)))
       ansi/SYNC_END))

(def default-size
  "The size assumed when the terminal doesn't report one."
  {:rows 24 :cols 80})

(def ^:private max-wait-ms
  "How long to wait for a key at most before checking for a terminal resize."
  100)

(defn- run-loop
  "Renders the prompt and handles keys until it finishes. Keeps the last
  state in `current` so that it can be cleaned up when an error is thrown."
  [{:keys [init view tick fullscreen?] update-fn :update} current]
  (let [read-key (tty/key-reader)
        now #(System/currentTimeMillis)]
    (loop [state init
           frame nil
           next-tick (some->> (:ms tick) (+ (now)))]
      (vreset! current {:state state :frame frame})
      (if (::outcome state)
        state
        (let [size (or (tty/get-terminal-size) default-size)
              lines (fit-lines (view state size) size)
              frame (if (= {:lines lines :size size} frame)
                      frame
                      (do
                        (tty/write! (paint frame lines size fullscreen?))
                        {:lines lines :size size}))
              _ (vreset! current {:state state :frame frame})
              ;; At least 1ms, as JLine waits forever on a timeout of 0
              key (read-key (cond-> max-wait-ms
                              next-tick (min (max 1 (- next-tick (now))))))
              state (cond
                      (= ::tty/timeout key) state
                      ;; The input has ended
                      (nil? key) (skip state)
                      (= :ctrl-c key) (abort state)
                      :else (update-fn (assoc state ::size size) key))
              ;; Also ticks while keys are pressed, e.g. when holding one
              tick? (and next-tick
                         (>= (now) next-tick)
                         (not (::outcome state)))]
          (recur (cond-> state tick? ((:f tick)))
            frame
            (if tick? (+ (now) (:ms tick)) next-tick)))))))

(defn run!
  "Runs an interactive prompt, returning its value. Options:

  - `:init`        the initial state
  - `:update`      `(fn [state key])` returning the next state, finish the
                   prompt with `submit` or `skip`. See `tty/key-reader` for keys.
                   The state has the terminal's size under `::size`.
  - `:view`        `(fn [state size])` returning the lines of the prompt, where
                   size is the terminal's `{:rows :cols}`
  - `:record`      `(fn [state outcome])` returning a line that remains in
                   place of the prompt once it finishes, or nil for none. The
                   outcome is `:submitted`, `:skipped` or `:aborted`.
  - `:tick`        `{:ms 100 :f (fn [state])}` to update the state every ms,
                   e.g. to animate a spinner
  - `:fullscreen?` renders on the alternate screen, the record is printed on
                   the normal screen afterwards

  Returns nil when skipped. Pressing ctrl+c aborts the prompt, throwing an
  ex-info with `{:type ::interrupted}`."
  [{:keys [record fullscreen?] :as prompt}]
  (let [current (volatile! nil)
        state
        (tty/run-in-raw-mode {:signals? false}
          (fn []
            (tty/detect-background!)
            (tty/write! ansi/HIDE_CURSOR (when fullscreen? ansi/ALT_SCREEN))
            (try
              (run-loop prompt current)
              (finally
                (let [{:keys [state frame]} @current
                      outcome (::outcome state)
                      cols (:cols (or (tty/get-terminal-size) default-size))]
                  (tty/write!
                    (if fullscreen?
                      ansi/NORMAL_SCREEN
                      (erase frame cols))
                    (when-let [line (and outcome record (record state outcome))]
                      (str (ansi/truncate line (dec cols)) "\r\n"))
                    ansi/SHOW_CURSOR))))))]
    (case (::outcome state)
      :submitted (::value state)
      :skipped nil
      :aborted (throw (ex-info "Interrupted" {:type ::interrupted})))))
