(ns prstack.main
  (:require
    [prstack.commands.create-prs :as create-prs-command]
    [prstack.commands.list :as list-command]
    [prstack.commands.machete :as machete-command]
    [prstack.commands.sync :as sync-command]
    [prstack.utils :as u]))

(def commands
  [list-command/command
   create-prs-command/command
   sync-command/command
   machete-command/command])

(defn run! [args]
  (if-let [command (u/find-first #(= (:name %) (first args)) commands)]
    ((:exec command) (rest args))
    (do
      (println (u/colorize :red "Error") "unknown command" (first args))
      (System/exit 1))))
