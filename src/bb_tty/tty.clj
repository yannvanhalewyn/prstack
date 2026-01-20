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

(defn replace-last-line [new-text]
  (print (str ansi/CURSOR_UP ansi/CURSOR_UP "\r" ansi/CLEAR_LINE new-text "\r\n"))
  (flush))

(defn read-single-char []
  (.read System/in))

;; Maybe use (not (nil? (System/getenv "USE_BBLGUM")))?
(def use-bblgum? true)

(defn prompt-yes [prompt]
  (print prompt)
  (flush)
  (if use-bblgum?
   (b/gum :confirm :as :bool)
   (in-raw-mode
     (= (read-single-char) (int \y)))))
