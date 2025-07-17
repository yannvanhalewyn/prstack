(ns prstack.utils
  (:require
    [babashka.process :as p]
    [clojure.string :as str]))

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

(defn run-cmd [cmd]
  (-> (p/process cmd {:out :string})
    p/check
    :out
    str/trim))

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
