(ns prstack.main
  (:gen-class)
  (:require
    [bb-tty.ansi :as ansi]
    [prstack.cli.commands.create-prs :as commands.create-prs]
    [prstack.cli.commands.feature-base :as commands.feature-base]
    [prstack.cli.commands.list :as commands.list]
    [prstack.cli.commands.status :as commands.status]
    [prstack.cli.commands.sync :as commands.sync]
    [prstack.init :as init]
    [prstack.tui.app :as tui.app]
    [prstack.utils :as u]))

(def commands
  [commands.status/command
   commands.list/command
   commands.create-prs/command
   commands.sync/command
   commands.feature-base/command])

(defn- parse-global-opts
  "Parses global options from args.
  Returns [global-opts remaining-args]"
  [args]
  (loop [opts {}
         [arg & more :as remaining] args]
    (cond
      (nil? arg)
      [opts []]

      (= "--project-dir" arg)
      (recur (assoc opts :project-dir (first more)) (rest more))

      :else
      [opts remaining])))

(defn- print-help
  ([]
   (println "Usage: prstack [options] <command> [command-options]")
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
   (println "Global Options:")
   (println "  -C, --project-dir <dir>  Run as if prstack was started in <dir>")
   (println "  -h, --help               Show this help message and exit")
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
  (let [[global-opts remaining-args] (parse-global-opts args)]
    (cond
      (empty? remaining-args)
      (tui.app/run!)

      (some #{"-h" "--help"} remaining-args)
      (if-let [command (u/find-first #(= (:name %) (first remaining-args)) commands)]
        (print-help command)
        (print-help))

      :else
      (do
        ;; Ensure initialization for all CLI commands
        (init/ensure-initialized!)
        (if-let [command (u/find-first #(= (:name %) (first remaining-args)) commands)]
          (if-let [subcommands (:subcommands command)]
            ;; Command has subcommands, check for subcommand
            (let [subcommand-name (second remaining-args)]
              (if (nil? subcommand-name)
                (print-help command)
                (if-let [subcmd (u/find-first #(= (:name %) subcommand-name) subcommands)]
                  ((:exec subcmd) (drop 2 remaining-args) global-opts)
                  (do
                    (println (ansi/colorize :red "Error") "Unknown subcommand:" subcommand-name "\n")
                    (print-help command)
                    (System/exit 1)))))
            ;; Command has no subcommands, execute directly
            ((:exec command) (rest remaining-args) global-opts))
          (do
            (println (ansi/colorize :red "Error") "Unknown command:" (first remaining-args) "\n")
            (print-help)
            (System/exit 1)))))))
