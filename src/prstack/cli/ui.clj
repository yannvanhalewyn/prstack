(ns prstack.cli.ui
  (:require
    [bb-tty.ansi :as ansi]
    [prstack.github :as github]
    [prstack.stack :as stack]
    [prstack.vcs :as vcs]))

(defn- format-change
  "Formats the branch as part of a stack"
  ([vcs change]
   (format-change vcs change {}))
  ([vcs change {:keys [trunk? feature-base?]}]
   (str
     (cond
       trunk?        " ◆ "  ; diamond for trunk
       feature-base? " ◉ "  ; fisheye for feature base
       :else         " \ue0a0 ") ; git branch icon
     (ansi/colorize :blue (vcs/local-branchname vcs change)))))

(defn- print-stack-section
  "Prints a single stack with optional PR information."
  [vcs stack config {:keys [include-prs? max-width header]}]
  (when header
    (println (ansi/colorize :cyan header)))
  ;; Print each branch in the stack (except the last one which is the base)
  (let [trunk-branch (:vcs-config/trunk-branch (vcs/vcs-config vcs))]
    (doseq [[cur-change prev-change]
            (partition 2 1 stack)]
      (if include-prs?
        (let [head-branch (vcs/local-branchname vcs cur-change)
              base-branch (vcs/local-branchname vcs prev-change)
              pr (github/find-pr head-branch base-branch)
              formatted-branch (format-change vcs cur-change)
              padded-branch (format (str "%-" max-width "s") formatted-branch)]
          (println padded-branch
            (cond
              pr
              (str (ansi/colorize :green "✔") " PR Found"
                   (ansi/colorize :gray (str " (" (:pr/number pr) ")")))
             ;; TODO Show if 'needs push'
              (not= head-branch trunk-branch)
              (str (ansi/colorize :red "X") " No PR Found")
              :else "")))
        (println (format-change vcs cur-change))))
    ;; Print the base branch at the bottom
    (let [base-change (last stack)
          base-branch (vcs/local-branchname vcs base-change)
          feature-base-branches (:feature-base-branches config)
          is-trunk? (= base-branch trunk-branch)
          is-feature-base? (contains? feature-base-branches base-branch)]
      (println (format-change vcs base-change
                 {:trunk? is-trunk?
                  :feature-base? is-feature-base?}))))
  (println))

(defn print-stacks
  "Prints regular stacks and optionally feature base stacks.

  stacks must be a map with :regular-stacks and :feature-base-stacks"
  [stacks vcs config {:keys [include-prs?] :as opts}]
  (let [{:keys [regular-stacks feature-base-stacks]} stacks

        all-stacks (concat regular-stacks feature-base-stacks)

        max-width
        (when-let [counts
                   (seq
                     (mapcat #(map (comp count (partial format-change vcs)) %)
                       (stack/reverse-stacks all-stacks)))]
          (apply max counts))]

    (when-not (seq regular-stacks)
      (println (ansi/colorize :cyan "No stacks detetected")))

    ;; Print regular stacks
    (let [reversed-stacks (stack/reverse-stacks regular-stacks)]
      (doseq [[i stack] (map-indexed vector reversed-stacks)]
        (print-stack-section vcs stack config
          (assoc opts
            :max-width max-width
            :header (str "\uf51e "
                         (if (zero? i) "Current Stack" "Other Stack")
                         " (" (dec (count stack)) ")")))))

    ;; Print feature base stacks
    (when (seq feature-base-stacks)
      (println (ansi/colorize :cyan "\n\uf126 Feature Base Branches"))
      (let [reversed-stacks (stack/reverse-stacks feature-base-stacks)]
        (doseq [stack reversed-stacks]
          (print-stack-section vcs stack config
            (assoc opts :max-width max-width :header nil)))))))
