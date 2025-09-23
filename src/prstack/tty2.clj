(ns prstack.tty2
  (:require
    [babashka.process :as p]
    [clojure.string :as str]))

(def CURSOR_UP "\u001b[1A") ;; ]
(def CLEAR_LINE "\u001b[2K") ;; ]

(defn run-in-raw-mode [f]
  (let [original-state (-> (p/shell {:out :string} "stty -g")
                         :out str/trim)]
    (try
      (p/shell "stty raw -echo")
      (f)
      (finally
        (p/shell (str "stty " original-state))))))

(defmacro in-raw-mode [& body]
  `(run-in-raw-mode (fn [] ~@body)))

(defn replace-last-line [new-text]
  (print (str CURSOR_UP CURSOR_UP "\r" CLEAR_LINE new-text "\r\n" "Hit 'q' to quit." "\r\n"))
  (flush))

(defn read-single-char []
  (.read System/in))

(defn prompt-yes [prompt]
  (print prompt " (y/n): ")
  (flush)
  (in-raw-mode
    (= (read-single-char) (int \y))))

(defn test []
  (in-raw-mode
    (println (prompt-yes "Are you sure?"))))
