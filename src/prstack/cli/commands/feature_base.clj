(ns prstack.cli.commands.feature-base
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [clojure.string :as str]
    [prstack.config :as config]
    [prstack.utils :as u]))

(defn- get-all-branches
  "Returns a list of all local branches."
  []
  (let [output (u/run-cmd ["git" "branch" "--list"])]
    (into []
      (comp
        (map str/trim)
        (map #(str/replace % #"^\*\s+" ""))
        (remove #(str/starts-with? % "("))
        (remove empty?))
      (str/split-lines output))))

(defn- targetted-branch [args {:keys [prompt get-candidates-fn]}]
  (if (seq args)
    (first args)
    (let [branches (get-candidates-fn)]
      (when-not (seq branches)
        (println (ansi/colorize :red "Error") "No branches found")
        (System/exit 1))
      (tty/prompt-filter
        {:prompt prompt
         :options branches
         :limit 1}))))

(defn- list-feature-base-branches [_args]
  (doseq [branch (:feature-base-branches (config/read-local))]
    (println branch)))

(defn- add-feature-base-branch [args]
  (let [branch-name (targetted-branch args
                      {:prompt "Select feature base branch"
                       :get-candidates-fn get-all-branches})
        config (config/read-local)
        existing-feature-bases (:feature-base-branches config)]
    (when-not branch-name
      (println (ansi/colorize :red "Error") "No branch selected")
      (System/exit 1))
    (when (contains? existing-feature-bases branch-name)
      (println (ansi/colorize :yellow "Warning")
        "Branch" (ansi/colorize :cyan branch-name)
        "is already a feature base branch")
      (System/exit 0))
    ;; Add the branch to feature-base-branches
    (let [updated-config (update config :feature-base-branches conj branch-name)]
      (config/write-local updated-config)
      (println (ansi/colorize :green "✔") "Added" (ansi/colorize :cyan branch-name) "as a feature base branch"))))

(defn- remove-feature-base-branch [args]
  (let [config (config/read-local)
        existing-feature-bases (:feature-base-branches config)
        feature-bases-vec (vec existing-feature-bases)]
    (when-not (seq feature-bases-vec)
      (println (ansi/colorize :yellow "Warning") "No feature base branches configured")
      (System/exit 0))
    (let [branch-name
          (targetted-branch args
            {:prompt "Select feature base to remove"
             :get-candidates-fn (constantly (:feature-base-branches config))}) ]
      (when-not branch-name
        (println (ansi/colorize :red "Error") "No branch selected")
        (System/exit 1))
      (when-not (contains? existing-feature-bases branch-name)
        (println (ansi/colorize :yellow "Warning") "Branch" (ansi/colorize :cyan branch-name) "is not a feature base branch")
        (System/exit 0))
      ;; Remove the branch from feature-base-branches
      (let [updated-config (update config :feature-base-branches disj branch-name)]
        (config/write-local updated-config)
        (println (ansi/colorize :green "✔") "Removed" (ansi/colorize :cyan branch-name) "from feature base branches")))))

(def command
  {:name "feature-base"
   :description "Manage feature base branches"
   :subcommands
   [{:name "list"
     :description "List all the feature bases"
     :exec list-feature-base-branches}
    {:name "add"
     :description "Add a feature base branch"
     :exec add-feature-base-branch}
    {:name "remove"
     :description "Remove a feature base branch"
     :exec remove-feature-base-branch} ]})
