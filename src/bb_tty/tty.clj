(ns bb-tty.tty
  (:require
    [babashka.process :as p]
    [bb-tty.ansi :as ansi]
    [bblgum.core :as b]
    [clojure.string :as str]
    [prstack.utils :as u]))

(defn- has-terminal? []
  (try
    (u/shell ["stty" "-g"])
    true
    (catch Exception _
      false)))

(defn get-terminal-size []
  (when (has-terminal?)
    (try
      (let [result (u/shell ["stty" "size"])]
        (when-not (str/blank? result)
          (when-let [[rows cols] (str/split result #" ")]
            {:rows (Integer/parseInt rows)
             :cols (Integer/parseInt cols)})))
      (catch Exception _
        nil))))

(defn run-in-raw-mode [f]
  (if (has-terminal?)
    (let [original-state (u/shell ["stty" "-g"])]
      (try
        (u/shell ["stty" "raw" "-echo"])
        (f)
        (finally
          (u/shell ["stty" original-state]))))
    (do
      (println "Error: TTY mode requires a terminal. Please run this command in a terminal.")
      (System/exit 1))))

(defmacro in-raw-mode [& body]
  `(run-in-raw-mode (fn [] ~@body)))

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
