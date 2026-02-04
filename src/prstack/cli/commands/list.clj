(ns prstack.cli.commands.list
  (:require
    [prstack.cli.ui :as cli.ui]
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.system :as system]
    [prstack.ui :as ui]))

(defn- parse-opts [args]
  (loop [opts {}
         [cur & more] args]
    (cond
      (nil? cur) opts

      (#{"--all" "-a"} cur)
      (recur (assoc opts :all? true) more)
      (#{"--exclude-prs" "-e"} cur)
      (recur (assoc opts :exclude-prs? true) more)

      :else (recur opts more))))

(comment
  (parse-opts ["--all" "--exclude-prs"]))

(def command
  {:name "list"
   :description "Lists the current PR stack"
   :flags [["--all" "-a" "Looks for any stacks, not just current"]
           ["--exclude-prs" "-e" "Don't fetch PRs (faster, useful for scripting)"]]
   :exec
   (fn list [args global-opts]
     (let [opts (parse-opts args)
           system (system/new (config/read-global) (config/read-local) global-opts)
           stacks
           (if (:all? opts)
             (stack/get-all-stacks system)
             (stack/get-current-stacks system))
           split-stacks (stack/split-feature-base-stacks stacks)]
       (cli.ui/print-stacks split-stacks
         (when-not (:exclude-prs? opts)
           (ui/fetch-prs-with-spinner)))))})

(comment
  (def sys-
    (system/new
      (config/read-global)
      (assoc (prstack.config/read-local) :vcs :jujutsu)
      {:project-dir "./tmp/parallel-branches"}))

  (stack/get-current-stacks sys-)
  (stack/get-all-stacks sys-))
