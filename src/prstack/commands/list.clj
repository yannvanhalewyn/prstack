(ns prstack.commands.list
  (:require
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.ui :as ui]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn parse-opts [args]
  {:all? (boolean (some #{"--all"} args))
   :include-prs? (boolean (some #{"--include-prs"} args))})

(def command
  {:name "list"
   :description "Lists the current PR stack"
   :flags [["--all" "-a" "Looks for any stacks, not just current"]
           ["--include-prs" "-I" "Also fetch a matching PR for each branch"]]
   :exec
   (fn list [args]
     (let [opts (parse-opts args)
           config (config/read-local)
           vcs-config (vcs/config)
           stacks
           (if (:all? opts)
             (stack/get-all-stacks vcs-config config)
             (stack/get-current-stacks vcs-config))
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
               (println padded-bookmark (cond
                                          pr-url
                                          (u/colorize :gray (str " (" pr-url ")"))
                                          (and (:include-prs? opts)
                                               (not= bookmark (:vcs-config/trunk-bookmark vcs-config)))
                                          (str (u/colorize :red "X") " No PR Found")
                                          :else "")))))
         (println))))})
