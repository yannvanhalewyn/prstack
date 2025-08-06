(ns prstack.ui
  (:require
    [prstack.utils :as u]))

(defn format-bookmark
  "Formats the bookmark as part of a stack at the given index"
  [i bookmark]
  (let [indent (str (apply str (repeat (* (dec i) 2) " ")) (when-not (zero? i) "└─ "))]
    (str (u/colorize :yellow indent)
         (u/colorize :blue bookmark))))

(defn print-stacks [stacks]
  (println (u/colorize :cyan "Detected the following stacks of bookmarks:\n"))
  (doseq [stack stacks]
    (doseq [[i bookmark] (map-indexed vector stack)]
      (println (format-bookmark i bookmark)))
    (println)))

