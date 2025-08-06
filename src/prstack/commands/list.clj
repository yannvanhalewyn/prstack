(ns prstack.commands.list
  (:require
   [prstack.ui :as ui]
   [prstack.utils :as u]
   [prstack.vcs :as vcs]))

(defn parse-opts [args]
  {:all? (some #{"--all"} args)
   :include-prs? (some #{"--include-prs"} args)})

(defn- into-stacks [leaves]
  (doall
   (for [leave leaves]
     (vcs/parse-stack (vcs/get-stack (first (:bookmarks leave)))))))

(def command
  {:name "list"
   :description "Lists the current PR stack"
   :flags [["--all" "-a" "Looks for any stacks, not just current"]
           ["--include-prs" "-I" "Also fetch a matching PR for each branch"]]
   :exec
   (fn list [args]
     (let [opts (parse-opts args)
           stacks
           (if (:all? opts)
             (into-stacks (vcs/get-leaves))
             [(vcs/parse-stack (vcs/get-stack))])
           max-width
           (when-let [counts
                      (seq
                       (mapcat #(map count (map-indexed ui/format-bookmark %))
                               stacks))]
             (apply max counts))]
       (doseq [stack stacks]
         (let [formatted-bookmarks (map-indexed ui/format-bookmark stack)]
           (doseq [[i [bookmark formatted-bookmark]]
                   (map-indexed vector
                     (map vector stack formatted-bookmarks))]
             (let [pr-url (when-let [base-branch (and (:include-prs? opts)
                                                      (get stack (dec i)))]
                            (vcs/find-pr bookmark base-branch))
                   padded-bookmark (format (str "%-" max-width "s") formatted-bookmark)]
               (println padded-bookmark (if pr-url (u/colorize :gray (str " (" pr-url ")")) "")))))
         (println))))})
