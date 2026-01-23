(ns prstack.cli.ui
  (:require
    [bb-tty.ansi :as ansi]
    [prstack.github :as github]
    [prstack.stack :as stack]
    [prstack.ui :as ui]))

(defn- print-stack-section
  "Prints a single stack with optional PR information."
  [stack {:keys [include-prs? max-width header]}]
  (when header
    (println (ansi/colorize :cyan header)))
  ;; Print each branch in the stack (except the last one which is the base)
  (doseq [[cur-change prev-change]
          (partition 2 1 stack)]
    (let [cur-branch (:change/selected-branchname cur-change)]
      (if include-prs?
        (let [head-branch cur-branch
              base-branch (:change/selected-branchname prev-change)
              [pr-info err] (github/find-pr head-branch base-branch)
              formatted-branch (ui/format-change cur-change)
              ;; Use uncolored text for width calculation
              uncolored-branch (ui/format-change cur-change {:no-color? true})
              visual-len (count uncolored-branch)
              padding-needed (- max-width visual-len)
              padding (apply str (repeat padding-needed " "))]
          (println (str formatted-branch padding " "
                        (ui/format-pr-info pr-info
                          {:error (:error/message err)}))))
        (println (ui/format-change cur-change)))))
  ;; Print the base branch at the bottom
  (println (ui/format-change (last stack)))
  (println))

(defn print-stacks
  "Prints regular stacks and optionally feature base stacks.

  stacks must be a map with :regular-stacks and :feature-base-stacks"
  [stacks opts]
  (let [{:keys [regular-stacks feature-base-stacks]} stacks
        all-stacks (concat regular-stacks feature-base-stacks)
        max-width
        (when-let [counts
                   (seq
                     (mapcat
                       (fn [stack]
                         (map (fn [change]
                                (count (ui/format-change change {:no-color? true})))
                           stack))
                       (stack/reverse-stacks all-stacks)))]
          (apply max counts))
        name-column-width (max 20 (or max-width 0))]

    (when-not (seq regular-stacks)
      (println (ansi/colorize :cyan "No stacks detetected")))

    ;; Print regular stacks
    (let [reversed-stacks (stack/reverse-stacks regular-stacks)]
      (doseq [[i stack] (map-indexed vector reversed-stacks)]
        (print-stack-section stack
          (assoc opts
            :max-width name-column-width
            :header (str "\uf51e "
                         (if (zero? i) "Current Stack" "Other Stack")
                         " (" (dec (count stack)) ")")))))

    ;; Print feature base stacks
    (when (seq feature-base-stacks)
      (println (ansi/colorize :cyan "\n\uf126 Feature Base Branches"))
      (let [reversed-stacks (stack/reverse-stacks feature-base-stacks)]
        (doseq [stack reversed-stacks]
          (print-stack-section stack
            (assoc opts :max-width name-column-width :header nil)))))))
