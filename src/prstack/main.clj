(ns prstack.main
  (:refer-clojure :exclude [run!])
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

(defn- print-help []
  (println "Usage: prstack <command> [options]")
  (println)
  (println "Commands:")
  (doseq [command commands]
    (println (format "  %-10s %s" (:name command) (:description command))))
  (println)
  (println "Options:")
  (println "  -h, --help  Show this help message and exit")
  (println))

(defn ^:lsp/allow-unused run! [args]
  (if (or (empty? args)
          (some #{"-h" "--help"} args))
    (print-help)
    (if-let [command (u/find-first #(= (:name %) (first args)) commands)]
      ((:exec command) (rest args))
      (do
        (println (u/colorize :red "Error") "Unknown command:" (first args) "\n")
        (print-help)
        (System/exit 1)))))
