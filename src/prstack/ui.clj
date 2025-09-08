(ns prstack.ui
  (:require
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn format-bookmark
  "Formats the bookmark as part of a stack at the given index"
  [i bookmark]
  (let [indent (str (apply str (repeat (* (dec i) 2) " ")) (when-not (zero? i) "└─ "))]
    (str (u/colorize :yellow indent)
         (u/colorize :blue bookmark))))

(defn print-stacks [stacks vcs-config {:keys [include-prs?]}]
  (if (seq stacks)
    (println (u/colorize :cyan "Detected the following stacks of bookmarks:\n"))
    (println (u/colorize :cyan "No stacks detetected")))
  (let [max-width
        (when-let [counts
                   (seq
                     (mapcat #(map count (map-indexed format-bookmark %))
                       stacks))]
          (apply max counts))]
    (doseq [stack stacks]
      (let [formatted-bookmarks (map-indexed format-bookmark stack) ]
        (doseq [[i [bookmark formatted-bookmark]]
                (map-indexed vector
                  (map vector stack formatted-bookmarks))]
          (if include-prs?
            (let [pr-url (when-let [base-branch (get stack (dec i))]
                           (vcs/find-pr bookmark base-branch))
                  padded-bookmark (format (str "%-" max-width "s") formatted-bookmark)]
              (println padded-bookmark
                (cond
                  pr-url
                  (u/colorize :gray (str " (" pr-url ")"))
                  (not= bookmark (:vcs-config/trunk-bookmark vcs-config))
                  (str (u/colorize :red "X") " No PR Found")
                  :else "")))
            (println formatted-bookmark))))
      (println))))

