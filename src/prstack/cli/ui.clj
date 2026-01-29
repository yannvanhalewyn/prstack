(ns prstack.cli.ui
  (:require
    [bb-tty.ansi :as ansi]
    [prstack.pr :as pr]
    [prstack.stack :as stack]
    [prstack.ui :as ui]))

(defn- print-stack-section
  "Prints a single stack with optional PR information."
  [stack prs {:keys [max-width header]}]
  (when header
    (println (ansi/colorize :cyan header)))
  ;; Print each branch in the stack (except the last one which is the base)
  (doseq [[cur-change prev-change]
          (partition 2 1 stack)]
    (let [[prs prs-err] prs
          cur-branch (:change/selected-branchname cur-change)
          head-branch cur-branch
          base-branch (:change/selected-branchname prev-change)
            ;; Find any PR for this head branch
          pr (when (and prs (not prs-err))
               (pr/find-pr prs head-branch))
            ;; Check if the PR has the wrong base branch
          wrong-base-branch (when (and pr (not= (:pr/base-branch pr) base-branch))
                              base-branch)
          formatted-branch (ui/format-change cur-change)
            ;; Use JLine's visual-length to calculate width from colorized text
          visual-len (ansi/visual-length formatted-branch)
          padding-needed (- max-width visual-len)
          padding (apply str (repeat padding-needed " "))]
      (println (str formatted-branch padding " "
                    (when prs ;; Nil without option `--include-prs`
                      (ui/format-pr-info pr
                        {:error (:error/message prs-err)
                         :wrong-base-branch wrong-base-branch}))))))
  ;; Print the base branch at the bottom
  (println (ui/format-change (last stack)))
  (println))

(defn print-stacks
  "Prints regular stacks and optionally feature base stacks.

  stacks must be a map with :regular-stacks and :feature-base-stacks"
  [split-stacks prs]
  (let [{:keys [regular-stacks feature-base-stacks]} split-stacks
        all-stacks (concat regular-stacks feature-base-stacks)
        max-width
        (when-let [counts
                   (seq
                     (mapcat
                       (fn [stack]
                         (map (fn [change]
                                (ansi/visual-length (ui/format-change change)))
                           stack))
                       (stack/reverse-stacks all-stacks)))]
          (apply max counts))
        name-column-width (max 20 (or max-width 0))]

    (when-not (seq regular-stacks)
      (println (ansi/colorize :cyan "No stacks detetected")))

    ;; Print regular stacks
    (let [reversed-stacks (stack/reverse-stacks regular-stacks)]
      (doseq [[i stack] (map-indexed vector reversed-stacks)]
        (print-stack-section stack prs
          {:max-width name-column-width
           :header (str "\uf51e "
                        (if (zero? i) "Current Stack" "Other Stack")
                        " (" (dec (count stack)) ")")})))

    ;; Print feature base stacks
    (when (seq feature-base-stacks)
      (println (ansi/colorize :cyan "\uf126 Feature Base Branches"))
      (let [reversed-stacks (stack/reverse-stacks feature-base-stacks)]
        (doseq [stack reversed-stacks]
          (print-stack-section stack prs
            {:max-width name-column-width}))))))
