(ns prstack.utils
  (:require
    [babashka.process :as p]
    [clojure.string :as str]))

(defn find-first [pred coll]
  (first (filter pred coll)))

(def colors
  {:reset "\033[0m"
   :bold "\033[1m"
   :green "\033[32m"
   :blue "\033[34m"
   :yellow "\033[33m"
   :cyan "\033[36m"
   :red "\033[31m"
   :gray "\033[90m"})

(def colorize
  (if (System/getenv "NO_COLORS")
    (fn [_color text]
      text)
    (fn [color text]
      (str (colors color) text (colors :reset)))))

(defn run-cmd [cmd & [{:keys [echo? dir]}]]
  (when echo?
    (println (colorize :gray (str "$ " (str/join " " cmd)))))
  (-> (p/process cmd {:out :string :dir dir})
    p/check
    :out
    str/trim))

(defn shell [cmd & [{:keys [echo? dir]}]]
  (when echo?
    (println (colorize :gray (str "$ " (str/join " " cmd)))))
  (-> (p/shell cmd {:out :string :dir dir})
    p/check
    :out
    str/trim))

(defn shell-out [cmd & [{:keys [echo?]}]]
  (when echo?
    (println (colorize :gray (str "$ " (str/join " " cmd)))))
  (-> (p/shell cmd {:inherit :true})
    p/check))

(defn shell-out-interactive
  "Runs an interactive shell command, properly handling terminal state"
  [cmd & [{:keys [echo?]}]]
  (when echo?
    (println (colorize :gray (str "$ " (str/join " " cmd)))))
  (if (System/getenv "TERM")
    ;; Restore normal terminal mode for interactive commands
    (let [original-state (try (-> (p/process ["stty" "-g"] {:out :string}) p/check :out str/trim)
                           (catch Exception _ nil))]
      (try
        ;; Restore normal terminal mode
        (when original-state
          (p/shell ["stty" original-state] {:inherit true}))
        ;; Run the interactive command
        (-> (p/shell cmd {:inherit :true})
          p/check)
        (finally
          ;; Return to raw mode if we were in it
          (when original-state
            (try
              (p/shell ["stty" "raw" "echo"] {:inherit true})
              (catch Exception _))))))
    ;; Fallback for non-terminal environments
    (-> (p/shell cmd {:inherit :true})
      p/check)))

(defn consecutive-pairs [coll]
  (map vector coll (rest coll)))

(defn vectorize [x]
  (cond
    (vector? x) x
    (sequential? x) (vec x)
    (nil? x) []
    :else (vector x)))

(defn indexed [x]
  (map-indexed vector x))
