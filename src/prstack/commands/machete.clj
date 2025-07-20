(ns prstack.commands.machete
  (:require
    [clojure.string :as str]
    [prstack.git :as git]
    [prstack.ui :as ui]
    [prstack.utils :as u]))

(defn machete-entry [i bookmark]
  (str (apply str (repeat (* i 2) " ")) bookmark))

(defn run []
  (let [bookmarks (git/parse-bookmark-tree (git/get-bookmark-tree))
        current-contents (slurp ".git/machete")
        added-contents (->> bookmarks
                         (drop 1)
                         (map-indexed #(machete-entry (inc %1) %2))
                         (str/join "\n"))]
    (println (u/colorize :cyan "Current Machete contents:\n"))
    (println current-contents)
    (println (u/colorize :cyan "\nAdding these lines\n"))
    (println added-contents)
    (spit ".git/machete" added-contents :append true)))
