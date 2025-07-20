(ns prstack.ui
  (:require
    [prstack.utils :as u]))

(defn format-bookmark
  "Formats the bookmark as part of a stack at the given index"
  [i bookmark]
  (let [indent (str (apply str (repeat (* (dec i) 2) " ")) (when-not (zero? i) "└─ "))]
    (str (u/colorize :yellow indent)
         (u/colorize :blue bookmark))))

(defn print-bookmark-tree [bookmarks]
  (println (u/colorize :cyan "Detected the following stack of bookmarks:\n"))
  (doseq [[i bookmark] (map-indexed vector bookmarks)]
    (println (format-bookmark i bookmark)))
  (println))

