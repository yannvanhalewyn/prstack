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

(defn list-stack []
  (let [include-prs? false
        bookmarks (vec (git/parse-bookmark-tree (git/get-bookmark-tree)))]
    (doseq [[i bookmark] (map-indexed vector bookmarks)]
      (let [pr-url (when-let [base-branch (and include-prs? (get bookmarks (dec i)))]
                     (git/find-pr bookmark base-branch))]
        (println (format-bookmark i bookmark) (if pr-url (u/colorize :gray (str " (" pr-url ")")) ""))))))

(defn create-prs []
  (let [bookmarks (git/parse-bookmark-tree (git/get-bookmark-tree))]

    ;; Log bookmark tree
    (println (u/colorize :cyan "Detected the following stack of bookmarks:\n"))
    (doseq [[i bookmark] (map-indexed vector bookmarks)]
      (println (format-bookmark i bookmark)))
    (println)

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
