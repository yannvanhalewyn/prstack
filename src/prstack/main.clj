(ns prstack.main
  (:gen-class)
  (:require
    [bb-tty.ansi :as ansi]
    [clojure.java.io :as io]
    [prstack.cli.commands.create-prs :as commands.create-prs]
    [prstack.cli.commands.feature-base :as commands.feature-base]
    [prstack.cli.commands.list :as commands.list]
    [prstack.cli.commands.status :as commands.status]
    [prstack.cli.commands.sync :as commands.sync]
    [prstack.init :as init]
    [prstack.tui.app :as tui.app]
    [prstack.utils :as u]
    [prstack.workspace :as workspace]))

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
         remaining []
         [arg & more] args]
    (cond
      (nil? arg)
      [opts remaining]

      (= "--project-dir" arg)
      (recur
        (assoc opts :project-dir (first more))
        remaining
        (rest more))

      (#{"--help" "-h"} arg)
      (recur (assoc opts :help? true) remaining more)

      :else
      (recur opts (conj remaining arg) more))))

(comment
  (parse-global-opts ["sync" "-h"])
  (parse-global-opts ["list" "--all" "--project-dir" "foo/bar"]))

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
   (println "      --project-dir <dir>  Run as if prstack was started in <dir>")
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

(defn- valid-project-dir? [project-dir]
  (or (nil? project-dir)
      (.isDirectory (io/file project-dir))))

(defn- resolve-dirs
  "Resolves the directory prstack operates on. Inside a repository, the project
  dir becomes the repository root, so running from a subfolder uses the
  repository's config. Outside of one, the directory is a workspace."
  [global-opts]
  (let [dir (or (:project-dir global-opts) ".")]
    (if-let [root (workspace/repo-root dir)]
      (assoc global-opts :project-dir root)
      (assoc global-opts :workspace-dir (.getCanonicalPath (io/file dir))))))

(defn- ensure-workspace-repos!
  "Exits when the workspace contains no repositories."
  [workspace-dir]
  (when (empty? (workspace/find-repos workspace-dir))
    (println (ansi/colorize :red "Error")
      "Not a repository, and no repositories found in" workspace-dir)
    (System/exit 1)))

(defn- exec-command!
  "Runs `command` on the repository, or on all repositories of the workspace
  when not inside a repository and the command supports it."
  [command-name command args global-opts]
  (if-let [ws-dir (:workspace-dir global-opts)]
    (if-let [workspace-exec (:workspace-exec command)]
      (workspace-exec args global-opts)
      (do
        (println (ansi/colorize :red "Error") ws-dir "is not a repository.")
        (println (ansi/colorize :yellow (str "prstack " command-name))
          "must run inside a repository, or use --project-dir.")
        (System/exit 1)))
    (do
      ;; Ensure initialization for all CLI commands
      (init/ensure-initialized! (:project-dir global-opts))
      ((:exec command) args global-opts))))

(defn -main [& args]
  (let [[global-opts remaining-args] (parse-global-opts args)]
    (when-not (valid-project-dir? (:project-dir global-opts))
      (println (ansi/colorize :red "Error") "Not a directory:" (:project-dir global-opts))
      (System/exit 1))
    (let [global-opts (resolve-dirs global-opts)]
      (cond
        (:help? global-opts)
        (if-let [command (u/find-first #(= (:name %) (first remaining-args)) commands)]
          (print-help command)
          (print-help))

        (empty? remaining-args)
        (do
          (some-> (:workspace-dir global-opts) ensure-workspace-repos!)
          (tui.app/run! global-opts))

        :else
        (if-let [command (u/find-first #(= (:name %) (first remaining-args)) commands)]
          (if-let [subcommands (:subcommands command)]
            ;; Command has subcommands, check for subcommand
            (let [subcommand-name (second remaining-args)]
              (if (nil? subcommand-name)
                (print-help command)
                (if-let [subcmd (u/find-first #(= (:name %) subcommand-name) subcommands)]
                  (exec-command! (str (:name command) " " subcommand-name)
                    subcmd (drop 2 remaining-args) global-opts)
                  (do
                    (println (ansi/colorize :red "Error") "Unknown subcommand:" subcommand-name "\n")
                    (print-help command)
                    (System/exit 1)))))
            ;; Command has no subcommands, execute directly
            (exec-command! (:name command) command (rest remaining-args) global-opts))
          (do
            (println (ansi/colorize :red "Error") "Unknown command:" (first remaining-args) "\n")
            (print-help)
            (System/exit 1)))))))
