(ns prstack.commands
  (:require
    [babashka.process :as p]
    [clojure.string :as str]
    [prstack.git :as git]
    [prstack.state :as state]
    [prstack.utils :as u]))

(defn format-bookmark [i bookmark]
  (let [indent (str (apply str (repeat (* (dec i) 2) " ")) (when-not (zero? i) "└─ "))]
    (str (u/colorize :yellow indent)
         (u/colorize :blue bookmark))))

(comment
  (state/read-state)
  (state/write-state
    {:prs
     [{:head "feature-2" :base "master" :id 1}
      {:head "feature-3" :base "feature-2" :id 2}]})
  (state/add-pr! "feature-4" "feature-3" 3)
  (state/add-pr! "feature-5" "feature-4" 4)
  (state/find-pr (state/read-state) "feature-3" "feature-2")

  (p/shell {:inherit true} "gh" "pr" "create"))

;; I need to keep track of which PRs were stacked before, and which branches
;; were created for them
;; So check bookmarks, see what the goal is, then for each head branch check if
;; there is already a PR. If not create one.
(defn create-prs []
  (let [state (state/read-state)
        bookmarks (git/parse-bookmark-tree (git/get-bookmark-tree))]

    ;; Log bookmark tree
    (println (u/colorize :cyan "Detected the following stack of bookmarks:\n"))
    (doseq [[i bookmark] (map-indexed vector bookmarks)]
      (println (format-bookmark i bookmark)))
    (println)

    ;; Create PRs
    (println (u/colorize :cyan "Let's create the PRs!\n"))
    (doseq [[base-branch head-branch] (u/consecutive-pairs bookmarks)]
      (let [pr (state/find-pr state head-branch base-branch)]
        (if pr
          (println
            (format "PR already exists for %s onto %s, skipping."
              (u/colorize :blue head-branch)
              (u/colorize :blue base-branch)))
          (when (u/prompt
                  (format "Create a PR for %s onto %s?"
                    (u/colorize :blue head-branch)
                    (u/colorize :blue base-branch)))
            (let [pr-id (git/create-pr head-branch base-branch)]
              (state/add-pr! head-branch base-branch pr-id))
            (println (u/colorize :green "\n✅ Created PR ...\n"))))))))

(defn machete-entry [i bookmark]
  (str (apply str (repeat (* i 2) " ")) bookmark))

(defn write-machete-file []
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
