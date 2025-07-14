(ns prstack.commands
  (:require
   [prstack.git :as git]
   [prstack.utils :as utils]
   [clojure.string :as str]))

(defn format-bookmark [i bookmark]
  (let [indent (str (apply str (repeat (* (dec i) 2) " ")) (when-not (zero? i) "└─ "))]
    (str (utils/colorize :yellow indent)
         (utils/colorize :blue bookmark))))

(defn create-prs []
  (let [bookmarks (git/parse-bookmark-tree (git/get-bookmark-tree))]

    (println (utils/colorize :cyan "Detected the following stack of bookmarks:\n"))
    (doseq [[i bookmark] (map-indexed vector bookmarks)]
      (println (format-bookmark i bookmark)))
    (println)

    (println (utils/colorize :cyan "Let's create the PRs!\n"))
    (doseq [[base-branch head-branch] (utils/consecutive-pairs bookmarks)]
      (when (utils/prompt
              (format "Create a PR for %s onto %s?"
               (utils/colorize :blue head-branch)
               (utils/colorize :blue base-branch)))
        ;;(utils/run-cmd ["nvim" (str head-branch "-onto-" base-branch ".txt")])
        ;;(git/create-pr head-branch base-branch)
        (println (utils/colorize :green "\n✅ Created PR ...\n"))))))

(defn machete-entry [i bookmark]
  (str (apply str (repeat (* i 2) " ")) bookmark))

(defn write-machete-file []
  (let [bookmarks (git/parse-bookmark-tree (git/get-bookmark-tree))
        current-contents (slurp ".git/machete")
        added-contents (->> bookmarks
                            (drop 1)
                            (map-indexed #(machete-entry (inc %1) %2))
                            (str/join "\n"))]
    (println (utils/colorize :cyan "Current Machete contents:\n"))
    (println current-contents)
    (println (utils/colorize :cyan "\nAdding these lines\n"))
    (println added-contents)
    (spit ".git/machete" added-contents :append true)))