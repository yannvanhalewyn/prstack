(ns prstack.commands
  (:require
    [prstack.vcs :as vcs]
    [prstack.utils :as u]
    [clojure.string :as str]))

(defn format-bookmark [i bookmark]
  (let [indent (str (apply str (repeat (* (dec i) 2) " ")) (when-not (zero? i) "└─ "))]
    (str (u/colorize :yellow indent)
         (u/colorize :blue bookmark))))

(defn create-prs []
  (let [bookmarks (vcs/parse-bookmark-tree (vcs/get-bookmark-tree))]

    (println (u/colorize :cyan "Detected the following stack of bookmarks:\n"))
    (doseq [[i bookmark] (map-indexed vector bookmarks)]
      (println (format-bookmark i bookmark)))
    (println)

    (println (u/colorize :cyan "Let's create the PRs!\n"))
    (doseq [[base-branch head-branch] (u/consecutive-pairs bookmarks)]
      (when (u/prompt
              (format "Create a PR for %s onto %s?"
                (u/colorize :blue head-branch)
                (u/colorize :blue base-branch)))
        ;;(u/run-cmd ["nvim" (str head-branch "-onto-" base-branch ".txt")])
        ;;(vcs/create-pr head-branch base-branch)
        (println (u/colorize :green "\n✅ Created PR ...\n"))))))

(defn machete-entry [i bookmark]
  (str (apply str (repeat (* i 2) " ")) bookmark))

(defn write-machete-file []
  (let [bookmarks (vcs/parse-bookmark-tree (vcs/get-bookmark-tree))
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
