(ns prstack.commands.create-prs
  (:require
    [prstack.stack :as stack]
    [prstack.ui :as ui]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn create-prs [{:keys [stack]}]
  (println (u/colorize :cyan "Let's create the PRs!\n"))
  (doseq [[base-branch head-branch] (u/consecutive-pairs
                                      (map #(first (:change/local-bookmarks %)) stack))]
    (let [pr-url (vcs/find-pr head-branch base-branch)]
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
          (vcs/create-pr! head-branch base-branch)
          (println (u/colorize :green "\n✅ Created PR ... \n")))))))

;; TODO also check if branched is pushed before making PR
(def command
  {:name "create"
   :description "Creates missing PRs in the current stack"
   :exec
   (fn create-prs-cmd [args]
     (let [ref (first args)
           vcs-config (vcs/config)
           stack
           (if ref
             (stack/get-stack ref vcs-config)
             (first (stack/get-current-stacks vcs-config)))]
       (ui/print-stacks [stack] vcs-config {:include-prs? true})
       (create-prs {:stack stack})))})
