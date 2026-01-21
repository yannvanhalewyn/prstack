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

(defn- print-stack-section
  "Prints a single stack with optional PR information."
  [stack vcs-config {:keys [include-prs? max-width header]}]
  (when header
    (println (ansi/colorize :cyan header)))
  ;; Print each branch in the stack (except the last one which is the base)
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
  ;; Print the base branch at the bottom
  (println (format-change (last stack) {:trunk? true}))
  (println))

(defn print-stacks
  "Prints regular stacks and optionally feature base stacks.
  
  stacks can be either:
    - A vector of stacks (legacy)
    - A map with :regular-stacks and :feature-base-stacks"
  [stacks vcs-config {:keys [include-prs?] :as opts}]
  (let [{:keys [regular-stacks feature-base-stacks]}
        (if (map? stacks)
          stacks
          {:regular-stacks stacks :feature-base-stacks []})
        
        all-stacks (concat regular-stacks feature-base-stacks)
        
        max-width
        (when-let [counts
                   (seq
                     (mapcat #(map (comp count format-change) %)
                       (stack/reverse-stacks all-stacks)))]
          (apply max counts))]
    
    (when-not (seq regular-stacks)
      (println (ansi/colorize :cyan "No stacks detetected")))
    
    ;; Print regular stacks
    (let [reversed-stacks (stack/reverse-stacks regular-stacks)]
      (doseq [[i stack] (map-indexed vector reversed-stacks)]
        (print-stack-section stack vcs-config
          (assoc opts
            :max-width max-width
            :header (str "\uf51e "
                         (if (zero? i) "Current Stack" "Other Stack")
                         " (" (dec (count stack)) ")")))))
    
    ;; Print feature base stacks
    (when (seq feature-base-stacks)
      (println (ansi/colorize :cyan (str "\n\uf126 Feature Base Branches")))
      (let [reversed-stacks (stack/reverse-stacks feature-base-stacks)]
        (doseq [stack reversed-stacks]
          (print-stack-section stack vcs-config
            (assoc opts :max-width max-width :header nil)))))))