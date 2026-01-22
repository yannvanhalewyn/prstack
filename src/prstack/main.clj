(ns prstack.main
  (:gen-class)
  (:require
    [bb-tty.ansi :as ansi]
    [prstack.cli.commands.create-prs :as commands.create-prs]
    [prstack.cli.commands.feature-base :as commands.feature-base]
    [prstack.cli.commands.list :as commands.list]
    [prstack.cli.commands.status :as commands.status]
    [prstack.cli.commands.sync :as commands.sync]
    [prstack.tui.app :as tui.app]
    [prstack.utils :as u]))

(def commands
  [commands.status/command
   commands.list/command
   commands.create-prs/command
   commands.sync/command
   commands.feature-base/command])

(defn- print-help
  ([]
   (println "Usage: prstack <command> [options]")
   (println)
   (println "When no <command> is specified, an interactive TTY app is started")
   (println)
   (println "Commands:")
   (doseq [command commands]
     (println (format "  %-15s %s" (:name command) (:description command)))
     (when-let [subcommands (:subcommands command)]
       (doseq [subcmd subcommands]
         (println (format "    %-13s %s" (str (:name command) " " (:name subcmd)) (:description subcmd))))))
   (println)
   (println "Options:")
   (println "  -h, --help  Show this help message and exit")
   (println))
  ([command]
   (println (format "Usage: prstack %s <subcommand> [options]" (:name command)))
   (println)
   (println (:description command))
   (println)
   (when-let [subcommands (:subcommands command)]
     (println "Subcommands:")
     (doseq [subcmd subcommands]
       (println (format "  %-10s %s" (:name subcmd) (:description subcmd))))
     (println))))

(defn -main [& args]
  (cond
    (empty? args)
    (tui.app/run!)

    (some #{"-h" "--help"} args)
    (if-let [command (u/find-first #(= (:name %) (first args)) commands)]
      (print-help command)
      (print-help))

    :else
    (if-let [command (u/find-first #(= (:name %) (first args)) commands)]
      (if-let [subcommands (:subcommands command)]
        ;; Command has subcommands, check for subcommand
        (let [subcommand-name (second args)]
          (if (nil? subcommand-name)
            (print-help command)
            (if-let [subcmd (u/find-first #(= (:name %) subcommand-name) subcommands)]
              ((:exec subcmd) (drop 2 args))
              (do
                (println (ansi/colorize :red "Error") "Unknown subcommand:" subcommand-name "\n")
                (print-help command)
                (System/exit 1)))))
        ;; Command has no subcommands, execute directly
        ((:exec command) (rest args)))
      (do
        (println (ansi/colorize :red "Error") "Unknown command:" (first args) "\n")
        (print-help)
        (System/exit 1)))))
