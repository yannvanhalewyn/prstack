(ns prstack.cli.commands.list
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [clojure.string :as str]
    [prstack.cli.ui :as cli.ui]
    [prstack.config :as config]
    [prstack.remote :as remote]
    [prstack.repo :as repo]
    [prstack.stack :as stack]
    [prstack.ui :as ui]
    [prstack.utils :as u]
    [prstack.workspace :as workspace]))

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

(defn- read-stacks [repo opts]
  (stack/split-feature-base-stacks
    (if (:all? opts)
      (stack/get-all-stacks repo)
      (stack/get-current-stacks repo))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace

(defn- read-repo
  "Reads the stacks and PRs of a workspace repo. Errors are captured in
  `:repo/error` so one broken repo doesn't prevent listing the others."
  [repo opts]
  (if-not (:repo/error repo)
    (try
      (assoc repo
        :repo/stacks (read-stacks repo opts)
        :repo/prs (when-not (:exclude-prs? opts)
                    (remote/list-prs (:repo/remote repo) {})))
      ;; Throwable so errors like StackOverflowError only affect this repo
      (catch Throwable e
        (assoc repo :repo/error (u/exception-message e))))
    repo))

(defn- has-stacks? [{:keys [regular-stacks feature-base-stacks]}]
  (or (stack/any-segments? regular-stacks)
      (seq feature-base-stacks)))

(defn- list-workspace [args {:keys [workspace-dir]}]
  (let [opts (parse-opts args)
        repo-dirs (workspace/find-repos workspace-dir)]
    (when (empty? repo-dirs)
      (println (ansi/colorize :red "Error")
        "Not a repository, and no repositories found in" workspace-dir)
      (System/exit 1))
    (let [repos (tty/spin
                  (fn []
                    (u/pmap-large-stack
                      #(-> (workspace/open-repo workspace-dir %)
                         (read-repo opts))
                      repo-dirs))
                  {:title (format "Reading %d repositories..." (count repo-dirs))})
          {with-stacks true without-stacks false}
          (group-by #(boolean (or (:repo/error %) (has-stacks? (:repo/stacks %))))
            repos)]
      (doseq [repo with-stacks]
        (cli.ui/print-repo-header (:repo/name repo))
        (if-let [err (:repo/error repo)]
          (do
            (println (ansi/colorize :red "Error") err)
            (println))
          (cli.ui/print-stacks (:repo/stacks repo) (:repo/prs repo))))
      (when (seq without-stacks)
        (println (ansi/colorize :gray
                   (str "No stacks in: "
                        (str/join ", " (map :repo/name without-stacks)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Command

(def command
  {:name "list"
   :description "Lists the current PR stack"
   :flags [["--all" "-a" "Looks for any stacks, not just current"]
           ["--exclude-prs" "-e" "Don't fetch PRs (faster, useful for scripting)"]]
   :exec
   (fn list [args global-opts]
     (let [opts (parse-opts args)
           project-dir (:project-dir global-opts)
           repo (repo/open project-dir (config/read-local project-dir))]
       (cli.ui/print-stacks (read-stacks repo opts)
         (when-not (:exclude-prs? opts)
           (ui/fetch-prs-with-spinner (:repo/remote repo))))))
   :workspace-exec list-workspace})

(comment
  (def repo-
    (repo/open "./tmp/parallel-branches"
      (assoc (prstack.config/read-local "./tmp/parallel-branches") :vcs :jujutsu)))

  (stack/get-current-stacks repo-)
  (stack/get-all-stacks repo-))
