(ns bb-tty.tty
  (:require
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

(defn prompt-confirm [prompt]
  (print prompt)
  (flush)
  (b/gum :confirm :as :bool))

(defn- gum-args [{:keys [prompt limit]}]
  (cond-> [:limit (or limit 1)]
    prompt (concat [:header prompt])))

(defn prompt-pick
  [{:keys [options render-option]
    :as opts
    :arglists '([prompt options render-option limit])}]
  (apply b/gum :choose (map (or render-option identity) options)
    (gum-args opts)))

(defn prompt-filter
  [{:keys [options render-option]
    :as opts
    :arglists '([prompt options render-option limit])}]
  (apply b/gum :filter (map (or render-option identity) options)
    (gum-args opts)))
