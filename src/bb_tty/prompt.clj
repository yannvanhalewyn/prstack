(ns bb-tty.prompt
  "Interactive prompts modeled on gum, see `bb-tty.inline`."
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.inline :as inline]
    [bb-tty.tty :as tty])
  (:import
    (java.util.concurrent ExecutionException)))

(defn- palette []
  (ansi/palette (tty/background)))

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
