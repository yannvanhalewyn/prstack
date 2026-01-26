(ns prstack.utils
  (:require
    [babashka.process :as p]
    [bb-tty.ansi :as ansi]
    [clojure.string :as str]))

(defn find-first [pred coll]
  (first (filter pred coll)))

(defn indexed
  "Returns an list of tuples of the form [index item]. Returns a transducer when called with no arguments"
  [coll]
  (map-indexed vector coll))

(defn find-index [pred coll]
  (some
    (fn [[idx el]]
      (when (pred el)
        idx))
    (indexed coll)))

(defn dissoc-in
  "Dissoc's the element at path in coll"
  [coll path]
  (if (= 1 (count path))
    (dissoc coll (first path))
    (update-in coll (butlast path) dissoc (last path))))

(defn indexed [x]
  (map-indexed vector x))

(defn consecutive-pairs [coll]
  (map vector coll (rest coll)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shell

(defn binary-exists? [binary-name]
  (try
    (let [result (-> (ProcessBuilder. ["which" binary-name])
                   (.start)
                   (.waitFor))]
      (zero? result))
    (catch Exception _
      false)))

(defn run-cmd [cmd & [{:keys [echo? dir]}]]
  (when echo?
    (println (ansi/colorize :gray (str "$ " (str/join " " cmd)))))
  (-> (p/process cmd {:out :string :dir dir})
    p/check
    :out
    str/trim))

(defn shell [cmd & [{:keys [echo? dir]}]]
  (when echo?
    (println (ansi/colorize :gray (str "$ " (str/join " " cmd)))))
  (-> (p/shell cmd {:out :string :dir dir})
    p/check
    :out
    str/trim))

(defn shell-out [cmd & [{:keys [echo?]}]]
  (when echo?
    (println (ansi/colorize :gray (str "$ " (str/join " " cmd)))))
  (-> (p/shell cmd {:inherit :true})
    p/check))

(defn shell-out-interactive
  "Runs an interactive shell command, properly handling terminal state"
  [cmd & [{:keys [echo?]}]]
  (when echo?
    (println (ansi/colorize :gray (str "$ " (str/join " " cmd)))))
  (if (System/getenv "TERM")
    ;; Restore normal terminal mode for interactive commands
    (let [original-state (try (-> (p/process ["stty" "-g"] {:out :string}) p/check :out str/trim)
                           (catch Exception _ nil))]
      (try
        (when original-state
          (p/shell ["stty" original-state] {:inherit true}))
        (-> (p/shell cmd {:inherit :true})
          p/check)
        (finally
          (when original-state
            (try
              (p/shell ["stty" "raw" "echo"] {:inherit true})
              (catch Exception _))))))
    ;; Fallback for non-terminal environments
    (-> (p/shell cmd {:inherit :true})
      p/check)))
