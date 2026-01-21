(ns prstack.cli.ui
  (:require
    [bb-tty.ansi :as ansi]
    [prstack.github :as github]
    [prstack.stack :as stack]
    [prstack.vcs :as vcs]))

(defn- format-change
  "Formats the branch as part of a stack"
  ([change]
   (format-change change {}))
  ([change {:keys [trunk?]}]
   (str
     (if trunk?
       " \ue729 " ; trunk icon (same as TUI)
       " \ue0a0 ") ; git branch icon (same as TUI)
     (ansi/colorize :blue (vcs/local-branchname change)))))

(defn print-stacks [stacks vcs-config {:keys [include-prs?]}]
  (if-not (seq stacks)
    (println (ansi/colorize :cyan "No stacks detetected"))
    (let [;; Reverse stacks for display (leaf at top, trunk at bottom)
          reversed-stacks (stack/reverse-stacks stacks)
          max-width
          (when-let [counts
                     (seq
                       (mapcat #(map (comp count format-change) %)
                         reversed-stacks))]
            (apply max counts))]
      (doseq [[i stack] (map-indexed vector reversed-stacks)]
        ;; Print stack header (Current Stack vs Other Stack)
        (println (ansi/colorize :cyan
                   (str "\uf51e "
                        (if (zero? i) "Current Stack" "Other Stack")
                        " (" (dec (count stack)) ")")))
        ;; Print each branch in the stack (except the last one which is trunk)
        (doseq [[cur-change prev-change]
                (partition 2 1 stack)]
          (if include-prs?
            (let [head-branch (vcs/local-branchname cur-change)
                  base-branch (vcs/local-branchname prev-change)
                  pr (github/find-pr head-branch base-branch)
                  formatted-branch (format-change cur-change)
                  padded-branch (format (str "%-" max-width "s") formatted-branch)]
              (println padded-branch
                (cond
                  pr
                  (str (ansi/colorize :green "✔") " PR Found"
                       (ansi/colorize :gray (str " (" (:pr/number pr) ")")))
                  ;; TODO Show if 'needs push'
                  (not= head-branch (:vcs-config/trunk-branch vcs-config))
                  (str (ansi/colorize :red "X") " No PR Found")
                  :else "")))
            (println (format-change cur-change))))
        ;; Print the trunk at the bottom
        (println (format-change (last stack) {:trunk? true}))
        (println)))))