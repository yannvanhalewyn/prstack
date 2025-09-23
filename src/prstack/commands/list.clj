(ns prstack.commands.list
  (:require
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.ui :as ui]
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
           vcs-config (vcs/config)
           stacks
           (if (:all? opts)
             (stack/get-all-stacks vcs-config config)
             (stack/get-current-stacks vcs-config)) ]
       (ui/print-stacks stacks vcs-config opts)))})
