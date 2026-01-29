(ns prstack.cli.commands.list
  (:require
    [prstack.cli.ui :as cli.ui]
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.system :as system]
    [prstack.ui :as ui]))

(defn parse-opts [args]
  {:all? (boolean (some #{"--all"} args))
   :include-prs? (boolean (some #{"--include-prs"} args))})

(def command
  {:name "list"
   :description "Lists the current PR stack"
   :flags [["--all" "-a" "Looks for any stacks, not just current"]
           ["--include-prs" "-I" "Also fetch the matching PR for each branch"]]
   :exec
   (fn list [args]
     (let [opts (parse-opts args)
           system (system/new (config/read-global) (config/read-local))
           stacks
           (if (:all? opts)
             (stack/get-all-stacks system)
             (stack/get-current-stacks system))
           split-stacks (stack/split-feature-base-stacks stacks)]
       (cli.ui/print-stacks split-stacks
         (when (:include-prs? opts)
           (ui/fetch-prs-with-spinner)))))})

(comment
  (def sys-
    (system/new
      (config/read-global)
      (assoc (prstack.config/read-local) :vcs :jujutsu)))

  (stack/get-current-stacks sys-)
  (stack/get-all-stacks sys-))
