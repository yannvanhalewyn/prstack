(ns bb-tty.tty
  (:require
    [babashka.process :as p]
    [bb-tty.ansi :as ansi]
    [bblgum.core :as b]
    [clojure.string :as str]
    [prstack.utils :as u])
  (:import
    (org.jline.keymap BindingReader KeyMap)
    (org.jline.terminal Terminal TerminalBuilder)))

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

(defn run-in-raw-mode [f]
  (with-open [terminal (open-terminal)]
    (when (dumb? terminal)
      (println "Error: TTY mode requires a terminal. Please run this command in a terminal.")
      (System/exit 1))
    (let [original-attrs (.enterRawMode terminal)]
      (reset! current-terminal terminal)
      (try
        (f)
        (finally
          (reset! current-terminal nil)
          (.setAttributes terminal original-attrs))))))

(defmacro in-raw-mode [& body]
  `(run-in-raw-mode (fn [] ~@body)))

(def ^:private key-map
  "Maps escape sequences of special keys to keywords. Arrow keys are sent
  differently depending on the terminal's cursor key mode, so both are bound."
  (let [bind (fn [^KeyMap km k & seqs]
               (.bind km k ^"[Ljava.lang.CharSequence;" (into-array CharSequence seqs)))]
    (doto (KeyMap.)
      (bind :up "\u001b[A" "\u001bOA")
      (bind :down "\u001b[B" "\u001bOB")
      (bind :right "\u001b[C" "\u001bOC")
      (bind :left "\u001b[D" "\u001bOD")
      ;; Any other input is a regular key
      (.setNomatch ::char)
      (.setUnicode ::char)
      ;; How long to wait after an escape to see if it starts a sequence
      (.setAmbiguousTimeout 50))))

(defn key-reader
  "Returns a function that blocks until a key is pressed, returning a keyword
  for special keys (e.g. `:up`), a string for others (e.g. \"j\") or nil when
  the input has ended. Only works while running in raw mode."
  []
  (let [reader (BindingReader. (.reader ^Terminal @current-terminal))]
    (fn []
      (let [binding (.readBinding reader key-map)]
        (if (= ::char binding)
          (.getLastBinding reader)
          binding)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Prompts

(defn ^:lsp/allow-unused with-spinner [cmd {:keys [title]}]
  (b/gum :spin cmd :title title))

(def ^:private spinner-frames
  ["⠋" "⠙" "⠹" "⠸" "⠼" "⠴" "⠦" "⠧" "⠇" "⠏"])

(defn spin
  "Calls `f` while displaying a spinner with a title. Unlike `with-spinner`
  this doesn't require a shell command. Returns the result of `f`."
  [f {:keys [title]}]
  (if-not (System/console)
    (f)
    (let [done (promise)
          render (fn [s]
                   (binding [*out* *err*]
                     (print (str "\r" ansi/CLEAR_LINE s))
                     (flush)))
          spinner (future
                    (render ansi/HIDE_CURSOR)
                    (loop [i 0]
                      (render (str (ansi/colorize :magenta (nth spinner-frames (mod i (count spinner-frames))))
                                   " " title))
                      (when (= ::pending (deref done 80 ::pending))
                        (recur (inc i))))
                    (render ansi/SHOW_CURSOR))]
      (try
        (f)
        (finally
          (deliver done true)
          @spinner)))))

(defn- gum-args
  ([opts]
   (gum-args {} opts))
  ([base {:keys [prompt]}]
   (cond-> (into [] (mapcat identity base))
     prompt (concat [:header (str (ansi/colorize :white prompt))]))))

(defn prompt-confirm [{:keys [prompt]}]
  (:result (b/gum :confirm [(str (ansi/colorize :reset "") prompt)] :as :bool)))

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

(defn prompt-pick
  {:arglists '([{:keys [prompt options render-option limit]}])}
  [{:keys [options render-option limit]
    :or {limit 1}
    :as opts}]
  (cond->>
    (:result
      (apply b/gum :choose (map (or render-option identity) options)
        (gum-args {:limit limit} opts)))
    render-option
    (map (fn [result]
           (u/find-first #(= (render-option %) result) options)))
    (= limit 1) (first)))

(defn prompt-filter
  [{:keys [prompt options render-option limit]}]
  (cond->>
    (:result
      (apply b/gum :filter (map (or render-option identity) options)
        (gum-args (when limit {:limit limit}) {:prompt prompt})))
    (= limit 1) (first)))
