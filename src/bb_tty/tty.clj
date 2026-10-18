(ns bb-tty.tty
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.keys :as keys]
    [clojure.string :as str])
  (:import
    (org.jline.terminal Attributes$LocalFlag Terminal TerminalBuilder)
    (sun.misc Signal SignalHandler)))

(def ^:private current-terminal
  "The JLine terminal while running in raw mode, see `run-in-raw-mode`."
  (atom nil))

(def ^:private raw-mode-attrs
  "The terminal's `:original` and `:raw` attributes while in raw mode."
  (atom nil))

(defn- open-terminal ^Terminal []
  (-> (TerminalBuilder/builder)
    (.system true)
    ;; Avoids JLine warning when there is no TTY
    (.dumb true)
    ;; Skips probing the terminal on build. The probe writes queries to the
    ;; terminal and reads the responses from stdin, interfering with the
    ;; event loop.
    (.graphemeCluster false)
    (.build)))

(defn- dumb? [^Terminal terminal]
  (str/starts-with? (.getType terminal) Terminal/TYPE_DUMB))

(defn get-terminal-size
  "Returns the size of the terminal while running in raw mode."
  []
  (when-let [^Terminal terminal @current-terminal]
    (let [size (.getSize terminal)]
      (when (pos? (.getColumns size))
        {:rows (.getRows size)
         :cols (.getColumns size)}))))

(defn on-resize
  "Calls `f` whenever the terminal is resized. Returns a function that removes
  the handler. Uses `sun.misc.Signal` as JLine's `Terminal$Signal` isn't
  available in Babashka."
  [f]
  (let [signal (Signal. "WINCH")
        previous (Signal/handle signal
                   (reify SignalHandler
                     (handle [_ _] (f))))]
    #(Signal/handle signal previous)))

(defn run-in-raw-mode
  "Calls `f` with the terminal in raw mode, restoring it afterwards. With
  `:signals? false` keys like ctrl+c are read as keys instead of sending a
  signal to the process."
  ([f]
   (run-in-raw-mode {} f))
  ([{:keys [signals?] :or {signals? true}} f]
   (with-open [terminal (open-terminal)]
     (when (dumb? terminal)
       (println "Error: TTY mode requires a terminal. Please run this command in a terminal.")
       (System/exit 1))
     (let [original-attrs (.enterRawMode terminal)]
       (when-not signals?
         (.setAttributes terminal
           (doto (.getAttributes terminal)
             (.setLocalFlag Attributes$LocalFlag/ISIG false))))
       (reset! current-terminal terminal)
       (reset! raw-mode-attrs {:original original-attrs
                               :raw (.getAttributes terminal)})
       (try
         (f)
         (finally
           (reset! current-terminal nil)
           (reset! raw-mode-attrs nil)
           (.setAttributes terminal original-attrs)))))))

(defn outside-raw-mode
  "Calls `f` with the terminal as it was before entering raw mode, e.g. to run
  an editor, and returns to raw mode afterwards."
  [f]
  (let [^Terminal terminal @current-terminal
        {:keys [original raw]} @raw-mode-attrs]
    (.setAttributes terminal original)
    (try
      (f)
      (finally
        (.setAttributes terminal raw)))))

(defmacro in-raw-mode [& body]
  `(run-in-raw-mode (fn [] ~@body)))

(def ^:private pending
  "Bytes read from stdin that weren't parsed into keys yet. Kept between reads,
  so keys typed ahead reach the next prompt."
  (atom []))

(def ^:private poll-ms 10)

(def ^:private escape-timeout-ms
  "How long to wait for the rest of an escape sequence, after which a lone
  escape is the escape key."
  50)

(defn- now []
  (System/currentTimeMillis))

(defn- read-available!
  "Reads the bytes available on stdin without blocking, returning true when
  there were any. Reading stdin directly, rather than with JLine's reader,
  never leaves a blocking read behind. JLine reads on a background thread to
  support timeouts, which keeps waiting for input after a timeout and would
  take the next key from the next prompt, or from a program like an editor."
  []
  (let [in System/in
        n (.available in)]
    (when (pos? n)
      (let [buf (byte-array n)
            read (.read in buf 0 n)]
        (when (pos? read)
          (swap! pending into (map #(bit-and % 0xff)) (take read buf))
          true)))))

(defn- await-input!
  "Waits for input at most `timeout-ms`, or forever when nil. Returns true
  when there is input."
  [timeout-ms]
  (let [deadline (some-> timeout-ms (+ (now)))]
    (loop []
      (read-available!)
      (cond
        (seq @pending) true
        (and deadline (>= (now) deadline)) false
        :else (do (Thread/sleep (long poll-ms)) (recur))))))

(defn- await-more-input!
  "Waits at most `timeout-ms` for more input, returning true when it came."
  [timeout-ms]
  (let [deadline (+ (now) timeout-ms)]
    (loop []
      (cond
        (read-available!) true
        (>= (now) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(defn read-key
  "Waits for a key press and returns it, see `bb-tty.keys`. With a timeout in
  milliseconds, returns `::timeout` when no key was pressed in time. Only
  works while running in raw mode."
  ([]
   (read-key nil))
  ([timeout-ms]
   (if-not (await-input! timeout-ms)
     ::timeout
     (loop [complete? false]
       (if-let [[key remaining] (keys/parse @pending complete?)]
         (do (reset! pending remaining)
           key)
         ;; Waits for the rest of the sequence, if it doesn't come it's
         ;; complete
         (recur (not (await-more-input! escape-timeout-ms))))))))

(defn write!
  "Writes to the terminal while running in raw mode. Unlike printing to stdout,
  this also reaches the terminal when stdout is redirected."
  [& strs]
  (let [writer (.writer ^Terminal @current-terminal)]
    (.print writer ^String (apply str strs))
    (.flush writer)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Background

(def ^:private detected-background (atom nil))

(defn- latin-1
  "Converts bytes to a string with a character per byte, and back."
  [x]
  (if (string? x)
    (mapv int x)
    (apply str (map char x))))

(defn- query-background
  "Asks the terminal for its background color, returning the OSC 11 reply.
  The query is followed by a DA1 query (`\\e[c`) which every terminal answers,
  so its reply marks the end of the response, also when the terminal doesn't
  support OSC 11. The replies are taken out of the input, keeping keys typed
  in the meantime."
  []
  (write! "\u001b]11;?\u001b\\" "\u001b[c")
  (let [deadline (+ (now) 500)]
    (loop []
      (read-available!)
      (let [input (latin-1 @pending)]
        (if-let [da1 (re-find #"\u001b\[\?[\d;]*c" input)]
          (let [osc (re-find #"\u001b\]11;[^\u0007\u001b]*(?:\u0007|\u001b\\)" input)]
            (reset! pending
              (latin-1 (cond-> (str/replace-first input da1 "")
                         osc (str/replace-first osc ""))))
            (or osc ""))
          (if (< (now) deadline)
            (do (Thread/sleep (long poll-ms))
              (recur))
            ""))))))

(defn detect-background!
  "Detects whether the terminal has a `:light` or `:dark` background, once per
  process. Must run in raw mode before reading keys, as the terminal's reply
  arrives as input."
  []
  (when-not @detected-background
    (reset! detected-background
      (or (some-> (query-background) ansi/parse-background-reply)
          (ansi/parse-colorfgbg (System/getenv "COLORFGBG"))
          :dark))))

(defn background
  "Returns the `:light` or `:dark` background of the terminal, see
  `detect-background!`. Defaults to `:dark` when it wasn't detected."
  []
  (or @detected-background :dark))
