(ns prstack.commands.status
  (:require
    [prstack.commands.list :as commands.list]))

(def command
  {:name "status"
   :description "Displays the status of all the PR stacks"
   :exec
   (fn sync [args]
     ((:exec commands.list/command) (concat args ["--all" "--include-prs"])))})
