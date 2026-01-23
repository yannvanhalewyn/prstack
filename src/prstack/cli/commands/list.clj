(ns prstack.cli.commands.list
  (:require
    [prstack.cli.ui :as ui]
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.vcs :as vcs]))

(defn parse-opts [args]
  {:all? (boolean (some #{"--all"} args))
   :include-prs? (boolean (some #{"--include-prs"} args))})

(def command
  {:name "list"
   :description "Lists the current PR stack"
   :flags [["--all" "-a" "Looks for any stacks, not just current"]
           ["--include-prs" "-I" "Also fetch a matching PR for each branch"]]
   :exec
   (fn list [args]
     (let [opts (parse-opts args)
           config (config/read-local)
           vcs (vcs/make config)
           stacks
           (if (:all? opts)
             (stack/get-all-stacks vcs config)
             (stack/get-current-stacks vcs config))
           processed-stacks
           (stack/process-stacks-with-feature-bases vcs config stacks)]
       (ui/print-stacks processed-stacks opts)))})
