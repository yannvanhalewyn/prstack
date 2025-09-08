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
  (if (seq stacks)
    (println (u/colorize :cyan "Detected the following stacks of bookmarks:\n"))
    (println (u/colorize :cyan "No stacks detetected")))
  (doseq [stack stacks]
    (doseq [[i bookmark] (map-indexed vector stack)]
      (println (format-bookmark i bookmark)))
    (println)))

