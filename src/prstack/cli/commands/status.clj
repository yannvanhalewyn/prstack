(ns prstack.cli.commands.status
  (:require
    [prstack.cli.commands.list :as commands.list]))

(def command
  {:name "status"
   :description "Displays the status of all the PR stacks"
   :exec
   (fn sync [args global-opts]
     ((:exec commands.list/command) (concat args ["--all"]) global-opts))})