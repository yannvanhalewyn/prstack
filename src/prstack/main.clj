(ns prstack.main
  (:gen-class)
  (:require
    [bb-tty.ansi :as ansi]
    [prstack.cli.commands.create-prs :as commands.create-prs]
    [prstack.cli.commands.list :as commands.list]
    [prstack.cli.commands.machete :as commands.machete]
    [prstack.cli.commands.status :as commands.status]
    [prstack.cli.commands.sync :as commands.sync]
    [prstack.tui.app :as tui.app]
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
    (tui.app/run!)
    :else
    (if-let [command (u/find-first #(= (:name %) (first args)) commands)]
      ((:exec command) (rest args))
      (do
         (println (ansi/colorize :red "Error") "Unknown command:" (first args) "\n")
        (print-help)
        (System/exit 1)))))
