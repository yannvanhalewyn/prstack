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

(defn colorize [color text]
  (str (colors color) text (colors :reset)))

(defn run-cmd [cmd & [{:keys [echo? dir]}]]
  (when echo?
    (println (colorize :gray (str "$ " (str/join " " cmd)))))
  (-> (p/process cmd {:out :string :dir dir})
    p/check
    :out
    str/trim))

(defn shell-out [cmd & [{:keys [echo?]}]]
  (when echo?
    (println (colorize :gray (str "$ " (str/join " " cmd)))))
  (-> (p/shell cmd {:inherit :true})
    p/check))

(defn read-first-char []
  (when-let [input (read-line)]
    (when (seq input)
      (first input))))

(defn prompt [prompt]
  (print prompt " (y/n): ")
  (flush)
  (= (read-first-char) \y))

(defn consecutive-pairs [coll]
  (map vector coll (rest coll)))
