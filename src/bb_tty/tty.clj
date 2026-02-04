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

(defn with-spinner [cmd {:keys [title]}]
  (b/gum :spin cmd :title title))

(defn- gum-args
  ([opts]
   (gum-args {} opts))
  ([base {:keys [prompt]}]
   (cond-> (into [] (mapcat identity base))
     prompt (concat [:header (str (ansi/colorize :white prompt))]))))

(defn prompt-confirm [{:keys [prompt]}]
  (:result (b/gum :confirm [(str (ansi/colorize :reset "") prompt)] :as :bool)))

(defn prompt-pick
  {:arglists '([{:keys [prompt options render-option limit]}])}
  [{:keys [options render-option limit]
    :or {limit 1}
    :as opts}]
  (cond->
    (:result
      (apply b/gum :choose (map (or render-option identity) options)
        (gum-args {:limit limit} opts)))
    (= limit 1) (first)))

(defn prompt-filter
  [{:keys [prompt options render-option limit]}]
  (cond->>
    (:result
      (apply b/gum :filter (map (or render-option identity) options)
        (gum-args (when limit {:limit limit}) {:prompt prompt})))
    (= limit 1) (first)))
