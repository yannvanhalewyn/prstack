(ns bb-tty.tty
  (:require
    [babashka.process :as p]
    [bb-tty.ansi :as ansi]
    [bblgum.core :as b]
    [clojure.string :as str])
  (:import
    (org.jline.keymap BindingReader KeyMap)
    (org.jline.terminal Attributes$LocalFlag Terminal TerminalBuilder)
    (sun.misc Signal SignalHandler)))

(def ^:private current-terminal
  "The JLine terminal while running in raw mode, see `run-in-raw-mode`."
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
       (try
         (f)
         (finally
           (reset! current-terminal nil)
           (.setAttributes terminal original-attrs)))))))

(defmacro in-raw-mode [& body]
  `(run-in-raw-mode (fn [] ~@body)))

(def ^:private key-map
  "Maps escape sequences of special keys to keywords, e.g. `:up`, `:enter`,
  `:ctrl-c` or `:alt-b`. Arrow keys are sent differently depending on the
  terminal's cursor key mode, so both are bound."
  (let [km (KeyMap.)
        bind (fn [k & seqs]
               (.bind km k ^"[Ljava.lang.CharSequence;" (into-array CharSequence seqs)))]
    (bind :up "\u001b[A" "\u001bOA")
    (bind :down "\u001b[B" "\u001bOB")
    (bind :right "\u001b[C" "\u001bOC")
    (bind :left "\u001b[D" "\u001bOD")
    (bind :alt-right "\u001b[1;3C" "\u001b\u001b[C")
    (bind :alt-left "\u001b[1;3D" "\u001b\u001b[D")
    (bind :ctrl-right "\u001b[1;5C")
    (bind :ctrl-left "\u001b[1;5D")
    (bind :home "\u001b[H" "\u001bOH" "\u001b[1~" "\u001b[7~")
    (bind :end "\u001b[F" "\u001bOF" "\u001b[4~" "\u001b[8~")
    (bind :delete "\u001b[3~")
    (bind :shift-tab "\u001b[Z")
    (bind :enter "\r")
    (bind :tab "\t")
    (bind :backspace "\u007f" "\b")
    (bind :alt-backspace "\u001b\u007f")
    ;; A lone escape, sequences starting with it win when they follow quickly
    (bind :esc "\u001b")
    ;; Ctrl+letter sends the letter's position in the alphabet. Tab (ctrl+i),
    ;; enter (ctrl+m) and backspace (ctrl+h) are bound above.
    (doseq [c "abcdefgjklnopqrstuvwxyz"]
      (bind (keyword (str "ctrl-" c)) (str (char (- (int c) 96)))))
    ;; Alt+letter sends an escape before the letter
    (doseq [c "abcdefghijklmnopqrstuvwxyz"]
      (bind (keyword (str "alt-" c)) (str "\u001b" c)))
    (doto km
      ;; Any other input is a regular key
      (.setNomatch ::char)
      (.setUnicode ::char)
      ;; How long to wait after an escape to see if it starts a sequence
      (.setAmbiguousTimeout 50))))

(defn key-reader
  "Returns a function that blocks until a key is pressed, returning a keyword
  for special keys (e.g. `:up`), a string for others (e.g. \"j\") or nil when
  the input has ended. Called with a timeout in milliseconds, it returns
  `::timeout` when no key was pressed in time. Only works while running in raw
  mode."
  []
  (let [reader (BindingReader. (.reader ^Terminal @current-terminal))
        read-key (fn []
                   (let [binding (.readBinding reader key-map)]
                     (if (= ::char binding)
                       (.getLastBinding reader)
                       binding)))]
    (fn
      ([]
       (read-key))
      ([timeout-ms]
       (if (= -2 (.peekCharacter reader (long timeout-ms)))
         ::timeout
         (read-key))))))

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

(defn- query-background
  "Asks the terminal for its background color, returning the raw reply. The
  OSC 11 query is followed by a DA1 query (`\\e[c`) which every terminal
  answers, so its reply marks the end of the response, also when the terminal
  doesn't support OSC 11."
  [^Terminal terminal]
  (let [reader (.reader terminal)
        deadline (+ (System/currentTimeMillis) 500)
        sb (StringBuilder.)]
    (write! "\u001b]11;?\u001b\\" "\u001b[c")
    (loop []
      (let [remaining (- deadline (System/currentTimeMillis))
            c (if (pos? remaining) (.read reader remaining) -2)]
        (when-not (neg? c)
          (.append sb (char c))
          (when-not (re-find #"\u001b\[\?[\d;]*c$" (str sb))
            (recur)))))
    (str sb)))

(defn detect-background!
  "Detects whether the terminal has a `:light` or `:dark` background, once per
  process. Must run in raw mode before reading keys, as the terminal's reply
  arrives as input."
  []
  (when-not @detected-background
    (reset! detected-background
      (or (some-> @current-terminal query-background ansi/parse-background-reply)
          (ansi/parse-colorfgbg (System/getenv "COLORFGBG"))
          :dark))))

(defn background
  "Returns the `:light` or `:dark` background of the terminal, see
  `detect-background!`. Defaults to `:dark` when it wasn't detected."
  []
  (or @detected-background :dark))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Prompts

(defn- gum-args
  ([opts]
   (gum-args {} opts))
  ([base {:keys [prompt]}]
   (cond-> (into [] (mapcat identity base))
     prompt (concat [:header (str (ansi/colorize :white prompt))]))))

(defn prompt-input
  "Prompts for a single line of text. Returns nil when cancelled or empty."
  [{:keys [value placeholder] :as opts}]
  (let [{:keys [status result]}
        (apply b/gum :input
          (gum-args (cond-> {}
                      value (assoc :value value)
                      placeholder (assoc :placeholder placeholder))
            opts))]
    (when (zero? status)
      (not-empty (str/trim (str/join "\n" result))))))

(defn prompt-write
  "Prompts for multiple lines of text. Returns nil when cancelled or empty."
  [{:keys [prompt value placeholder]}]
  ;; Not using bblgum here as it drops empty lines from the output
  (let [{:keys [exit out]}
        (apply p/shell {:out :string :in :inherit :err :inherit :continue true}
          (cond-> ["gum" "write"]
            prompt (conj (str "--header=" (ansi/colorize :white prompt)))
            value (conj (str "--value=" value))
            placeholder (conj (str "--placeholder=" placeholder))))]
    (when (zero? exit)
      (not-empty (str/trim out)))))

(defn prompt-filter
  [{:keys [prompt options render-option limit]}]
  (cond->>
    (:result
      (apply b/gum :filter (map (or render-option identity) options)
        (gum-args (when limit {:limit limit}) {:prompt prompt})))
    (= limit 1) (first)))
