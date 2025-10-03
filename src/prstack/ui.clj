(ns prstack.ui
  (:require
    [prstack.github :as github]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn- format-change
  "Formats the branch as part of a stack at the given index"
  [i change]
  (let [indent (str (apply str (repeat (* (dec i) 2) " ")) (when-not (zero? i) "└─ "))]
    (str (u/colorize :yellow indent)
         (u/colorize :blue (vcs/local-branchname change)))))

(defn print-stacks [stacks vcs-config {:keys [include-prs?]}]
  (if (seq stacks)
    (println (u/colorize :cyan "Detected the following stacks:\n"))
    (println (u/colorize :cyan "No stacks detetected")))
  (let [max-width
        (when-let [counts
                   (seq
                     (mapcat #(map count (map-indexed format-change %))
                       stacks))]
          (apply max counts))]
    (doseq [stack stacks]
      (let [formatted-branches (map-indexed format-change stack) ]
        (doseq [[i [change formatted-branch]]
                (map-indexed vector
                  (map vector stack formatted-branches))]
          (if include-prs?
            (let [head-branch (vcs/local-branchname change)
                  pr (when-let [base-branch (vcs/local-branchname (get stack (dec i)))]
                       (github/find-pr head-branch base-branch))
                  padded-branch (format (str "%-" max-width "s") formatted-branch)]
              (println padded-branch
                (cond
                  pr
                  (str (u/colorize :green "✔") " PR Found"
                       (u/colorize :gray (str " (" (:pr/number pr) ")")))
                  ;; TODO Show if 'needs push'
                  (not= head-branch (:vcs-config/trunk-branch vcs-config))
                  (str (u/colorize :red "X") " No PR Found")
                  :else "")))
            (println formatted-branch))))
      (println))))
