(ns prstack.main
  (:require
    [prstack.app :as app]
    [prstack.commands.create-prs :as commands.create-prs]
    [prstack.commands.list :as commands.list]
    [prstack.commands.machete :as commands.machete]
    [prstack.commands.status :as commands.status]
    [prstack.commands.sync :as commands.sync]
    [prstack.utils :as u]))

(def commands
  [commands.status/command
   commands.list/command
   commands.create-prs/command
   commands.sync/command
   commands.machete/command ])

(defn- print-help []
  (println "Usage: prstack <command> [options]")
  (println)
  (println "When no <command> is specified, an interactive TTY app is started")
  (println)
  (println "Commands:")
  (doseq [command commands]
    (println (format "  %-10s %s" (:name command) (:description command))))
  (println)
  (println "Options:")
  (println "  -h, --help  Show this help message and exit")
  (println))

(defn -main [& args]
  (cond
    (some #{"-h" "--help"} args)
    (print-help)
    (empty? args)
    (app/run!)
    :else
    (if-let [command (u/find-first #(= (:name %) (first args)) commands)]
      ((:exec command) (rest args))
      (do
        (println (u/colorize :red "Error") "Unknown command:" (first args) "\n")
        (print-help)
        (System/exit 1)))))
