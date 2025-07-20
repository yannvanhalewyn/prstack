(ns prstack.main
  (:require
    [prstack.commands.create-prs :as create-command]
    [prstack.commands.list :as list-command]
    [prstack.commands.machete :as machete-command]
    [prstack.commands.sync :as sync-command]
    [prstack.utils :as utils]))

(defn run! [args]
  (let [command (first args)]
    (case command
      "list" (list-command/run (some #{"--include-prs"} args))
      "create" (create-command/run)
      "sync" (sync-command/run)
      "machete" (machete-command/run)
      (do
        (println (utils/colorize :red "Error") "unknown command" command)
        (System/exit 1)))))
