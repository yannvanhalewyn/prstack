(ns prstack.commands
  (:require
    [clojure.string :as str]
    [prstack.git :as git]
    [prstack.utils :as u]))

(defn- format-bookmark [i bookmark]
  (let [indent (str (apply str (repeat (* (dec i) 2) " ")) (when-not (zero? i) "└─ "))]
    (str (u/colorize :yellow indent)
         (u/colorize :blue bookmark))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Commands

(defn list-stack [include-prs?]
  (let [bookmarks (vec (git/parse-bookmark-tree (git/get-bookmark-tree)))
        formatted-bookmarks (map-indexed format-bookmark bookmarks)
        max-width (when (seq bookmarks)
                    (apply max (map count (map-indexed format-bookmark bookmarks))))]
    (doseq [[i [bookmark formatted-bookmark]] (map-indexed vector
                                                (map vector bookmarks formatted-bookmarks))]
      (let [pr-url (when-let [base-branch (and include-prs? (get bookmarks (dec i)))]
                     (git/find-pr bookmark base-branch))
            padded-bookmark (format (str "%-" max-width "s") formatted-bookmark)]
        (println padded-bookmark (if pr-url (u/colorize :gray (str " (" pr-url ")")) ""))))))

(defn- print-bookmark-tree [bookmarks]
  (println (u/colorize :cyan "Detected the following stack of bookmarks:\n"))
  (doseq [[i bookmark] (map-indexed vector bookmarks)]
    (println (format-bookmark i bookmark)))
  (println))

(defn create-prs []
  (let [bookmarks (git/parse-bookmark-tree (git/get-bookmark-tree))]

    ;; Log bookmark tree
    (print-bookmark-tree bookmarks)

    ;; Create PRs
    (println (u/colorize :cyan "Let's create the PRs!\n"))
    (doseq [[base-branch head-branch] (u/consecutive-pairs bookmarks)]
      (let [pr-url (git/find-pr head-branch base-branch)]
        (if pr-url
          (println
            (format "PR already exists for %s onto %s, skipping. (%s)"
              (u/colorize :blue head-branch)
              (u/colorize :blue base-branch)
              (u/colorize :gray pr-url)))
          (when (u/prompt
                  (format "Create a PR for %s onto %s?"
                    (u/colorize :blue head-branch)
                    (u/colorize :blue base-branch)))
            (git/create-pr! head-branch base-branch)
            (println (u/colorize :green "\n✅ Created PR ... \n"))))))))

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

(defn sync []
  (println (u/colorize :yellow "\nFetching branches from remote..."))
  (u/shell-out ["jj" "git" "fetch"]
    {:echo? true})

  (println (u/colorize :yellow "\nBumping local master to remote master..."))
  (u/shell-out ["jj" "bookmark" "set" "master" "-r" "master@origin"]
    {:echo? true})

  (when (u/prompt (format "\nRebase on %s?" (u/colorize :blue "master")))
    (u/shell-out ["jj" "rebase" "-d" "master"]
      {:echo? true}))

  (println (u/colorize :yellow "Pushing local tracked branches..."))
  (u/shell-out ["jj" "git" "push" "--tracked"] {:echo? true})

  (print-bookmark-tree (git/parse-bookmark-tree (git/get-bookmark-tree))))
