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
           ["--include-prs" "-I" "Also fetch the matching PR for each branch"]]
   :exec
   (fn list [args]
     (let [opts (parse-opts args)
           config (config/read-local)
           vcs (vcs/make config)
           stacks
           (if (:all? opts)
             (stack/get-all-stacks vcs config)
             (stack/get-current-stacks vcs config))
           split-stacks
           (stack/split-feature-base-stacks stacks)]
       (ui/print-stacks split-stacks opts)))})

(comment
  (do
    (def config- (assoc (prstack.config/read-local) :vcs :jujutsu))
    (def vcs- (prstack.vcs/make config-)))

  (stack/get-current-stacks vcs- config-)
  (stack/get-all-stacks vcs- config-))
