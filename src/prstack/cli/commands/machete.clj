(ns prstack.cli.commands.machete
  (:require
    [bb-tty.ansi :as ansi]
    [clojure.string :as str]
    [prstack.stack :as stack]
    [prstack.vcs :as vcs]))

(defn- machete-entry [i branch]
  (str (apply str (repeat (* i 2) " ")) branch))

(def command
  {:name "machete"
   :description "Writes the current PR stack to the .git/machete file"
   :exec
   (fn machete [_args]
     (let [vcs-config (vcs/config)
           stacks (stack/get-current-stacks vcs-config {})
           stack (first stacks)
           current-contents (slurp ".git/machete")
           added-contents (->> stack
                            (drop 1)
                            (map-indexed #(machete-entry (inc %1) %2))
                            (str/join "\n"))]
       (println (ansi/colorize :cyan "Current Machete contents:\n"))
       (println current-contents)
       (println (ansi/colorize :cyan "\nAdding these lines\n"))
       (println added-contents)
       (spit ".git/machete" added-contents :append true)))})
