(ns prstack.cli.commands.list
  (:require
    [prstack.cli.ui :as ui]
    [prstack.config :as config]
    [prstack.github :as github]
    [prstack.stack :as stack]
    [prstack.system :as system]))

(defn parse-opts [args]
  {:all? (boolean (some #{"--all"} args))
   :include-prs? (boolean (some #{"--include-prs"} args))})

(defn get-prs []
  ())

(def command
  {:name "list"
   :description "Lists the current PR stack"
   :flags [["--all" "-a" "Looks for any stacks, not just current"]
           ["--include-prs" "-I" "Also fetch the matching PR for each branch"]]
   :exec
   (fn list [args]
     (let [opts (parse-opts args)
           system (system/new (config/read-local))
           stacks
           (if (:all? opts)
             (stack/get-all-stacks system)
             (stack/get-current-stacks system))
           split-stacks
           (stack/split-feature-base-stacks stacks)
           [prs error] (when (:include-prs? opts) (github/list-prs))]
       (clojure.pprint/pprint prs)
       (ui/print-stacks split-stacks [prs error])))})

(comment
  (def sys-
    (system/new
      (assoc (prstack.config/read-local) :vcs :jujutsu)))

  (stack/get-current-stacks sys-)
  (stack/get-all-stacks sys-))
