(ns prstack.custom-command
  "This namespace is about providing 'custom command' functionality. Users can
  configure various commands in .edn files, these need to be parsed and
  executed. The commands can be run with various placeholder that will be
  substituted according to the current context. Thinks like SHAs, branchnames,
  PR numbers etc.. Additionally, since we can't declare piping multiple
  processes together in just a string (bb process can't run such commands), we
  add a few `:pipe` and `:and` keywords to pipe commands together.

  Example Configuration (~/.config/prstack/config.edn). This example shows
  current functionality and potential future extensions.

  Current functionality:
  ----------------------

  {:diffview-cmd [:pipe [\"git\" \"diff\" \"--color=always\" \"$from-sha..$to-sha\"]
                        [\"delta\"]]

   :keybindings
   {;; 'D' - Describe: generate PR description using AI and update the PR
    \"D\" [:shell \"git diff $from-sha..$to-sha | opencode -p 'Write a concise PR description for these changes' | gh pr edit $pr-number --body-file -\"]

    ;; 'e' - Edit the diff in your editor
    \"e\" [:pipe [\"git\" \"diff\" \"$from-sha..$to-sha\"] [\"vim\" \"-\"]]

    ;; 'b' - Open branch in browser
    \"b\" [:shell \"gh browse --branch $head-branch\"]

    ;; 'l' - View commit log between changes
    \"l\" [:pipe [\"git\" \"log\" \"--oneline\" \"$from-sha..$to-sha\"] [\"less\"]]

    ;; 'f' - Show files changed
    \"f\" [:shell \"git diff --stat $from-sha..$to-sha | less\"]

    ;; 'C' - Copy branch name to clipboard
    \"C\" [:shell \"echo -n $head-branch | pbcopy && echo 'Copied $head-branch to clipboard'\"]}}


  FUTURE EXTENSIONS (not yet implemented):
  ----------------------------------------

  The command system is designed to be extensible. Here's what future versions
  might support:

  {:commands
   {;; Confirmation prompt before destructive actions
    \"X\" {:cmd [:shell \"gh pr close $pr-number\"]
         :confirm {:message \"Are you sure you want to close PR #$pr-number?\"}}

    ;; User input prompts with placeholders
    \"R\" {:cmd [:shell \"gh pr review $pr-number --$action\"]
         :prompt {:var \"$action\"
                  :message \"Review action:\"
                  :options [\"approve\" \"request-changes\" \"comment\"]}}

    ;; Free-form text input
    \"T\" {:cmd [:shell \"gh pr edit $pr-number --title '$title'\"]
         :prompt {:var \"$title\"
                  :message \"New PR title:\"
                  :default \"$head-branch\"}}

    ;; Filter selection (fzf-like)
    \"G\" {:cmd [:shell \"git checkout $selected\"]
         :filter {:source [:shell \"git branch --list\"]
                  :var \"$selected\"
                  :message \"Select branch:\"}}

    ;; Capture command output for chaining
    \"A\" {:cmd [:shell \"gh pr view $pr-number --json author --jq .author.login\"]
         :capture {:var \"$author\"
                   :then [:shell \"echo 'Author: $author' | less\"]}}

    ;; Conditional execution based on context
    \"P\" {:when {:pr-exists true}
         :cmd [:shell \"gh pr view $pr-number --web\"]
         :else {:cmd [:shell \"echo 'No PR exists for $head-branch'\"]}}

    ;; Multi-step workflows
    \"W\" {:steps [{:cmd [:shell \"git diff $from-sha..$to-sha > /tmp/diff.patch\"]}
                 {:prompt {:var \"$message\" :message \"Commit message:\"}}
                 {:cmd [:shell \"git apply /tmp/diff.patch && git commit -m '$message'\"]}]}}}

  The extended form uses a map with :cmd plus additional options.
  Simple commands can still use the shorthand form (just the command spec).
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  "
  (:require
    [clojure.string :as str]
    [prstack.utils :as u]))

;; Command specification schema - supports multiple execution strategies
;; Examples:
;;   "git diff $from-sha..$to-sha"                    - Simple shell string
;;   ["git" "diff" "$from-sha..$to-sha"]              - Command vector
;;   [:shell "git diff $from-sha..$to-sha | less"]    - Explicit shell
;;   [:and ["git" "diff" "$from-sha..$to-sha"] ["sleep" "2"]]  - Pipeline
;;   [:pipe ["git" "diff" "$from-sha..$to-sha"] ["less" "-R"]]  - Pipeline
;;
;; Future extensions could add:
;;   :confirm - Prompt for confirmation before running
;;   :prompt - Ask for additional user input
;;   :filter - Select from a list (fzf-like)
;;   :capture - Capture command output for chaining
;;
;; Available placeholders for custom commands:
;;   $from-sha     - The commit SHA of the previous/base change
;;   $to-sha       - The commit SHA of the selected change
;;   $base-branch  - Alias for $from-branch
;;   $head-branch  - Alias for $to-branch
;;   $pr-number    - The PR number (if a PR exists for the selected change)
;;   $pr-url       - The PR URL (if a PR exists for the selected change)
(def Command
  [:or
   :string
   [:sequential [:string]]
   [:fn (fn [x]
          (and (sequential? x)
               (#{:shell :pipe :and} (first x))))]])

(defn- substitute-placeholders
  "Replaces placeholders in a command or string with actual values.
  Supports both $placeholder and :placeholder syntax."
  [cmd placeholders]
  (letfn [(replace-placeholders [s]
            (reduce (fn [s [placeholder value]]
                      (if value
                        (str/replace s placeholder value)
                        s))
              s placeholders))]
    (cond
      (vector? cmd) (mapv replace-placeholders cmd)
      (string? cmd) (replace-placeholders cmd)
      :else cmd)))

(defn build-placeholders
  "Builds placeholder map from context.
   Context can include:
     :from-sha, :to-sha         - Commit SHAs
     :from-branch, :to-branch   - Branch names
     :pr-number, :pr-url        - PR information

   Aliases for branch names:
     $head-branch = $to-branch   (the selected/head branch)
     $base-branch = $from-branch (the previous/base branch)"
  [{:keys [from-sha to-sha from-branch to-branch pr-number pr-url]}]
  (cond-> {}
    from-sha    (assoc "$from-sha" from-sha)
    to-sha      (assoc "$to-sha" to-sha)
    from-branch (assoc "$base-branch" from-branch)
    to-branch   (assoc "$head-branch" to-branch)
    pr-number   (assoc "$pr-number" (str pr-number))
    pr-url      (assoc "$pr-url" pr-url)))

(comment
  (substitute-placeholders ["git" "diff" "$from-sha..$to-sha"]
    (build-placeholders {:from-sha "abc123" :to-sha "def456"})))

(defn prepare-command
  "Prepares a command for execution by resolving placeholders and determining strategy.

  Returns a map with ::strategy and ::value keys:
    {::strategy :pipe|:shell, ::value <prepared-command>}

  Command spec can be:
    - String: \"git diff $from-sha..$to-sha\" -> {::strategy :shell ::value \"...\"}
    - Vector: [\"git\" \"diff\" ...] -> {::strategy :pipe ::value [[\"git\" \"diff\" ...]]}
    - [:shell \"...\"] -> {::strategy :shell ::value \"...\"}
    - [:pipe [...] [...]] -> {::strategy :pipe ::value [[...] [...]]}
    - Map with :cmd key -> extracts :cmd and processes

  Context is a map that can include:
    :from-sha, :to-sha     - Commit SHAs
    :from-branch, :to-branch - Branch names
    :pr-number, :pr-url    - PR information"
  [cmd-spec context]
  (let [placeholders (build-placeholders context)]
    (cond
      ;; Keyword-based dispatch [::shell ...] or [:pipe ...]
      (and (sequential? cmd-spec) (keyword? (first cmd-spec)))
      (let [[strategy & args] cmd-spec]
        (case strategy
          :pipe {::strategy ::pipe
                 ::value (mapv #(substitute-placeholders % placeholders) args)}
          :shell {::strategy ::shell
                  ::value (substitute-placeholders (first args) placeholders)}
          :and {::strategy ::and
                ::value (mapv #(substitute-placeholders % placeholders) args)}
          ;; Unknown strategy, treat as shell
          {::strategy ::shell
           ::value (substitute-placeholders cmd-spec placeholders)}))

      ;; String or vec - run as shell command
      (or (string? cmd-spec) (sequential? cmd-spec))
      {::strategy ::shell
       ::value (substitute-placeholders cmd-spec placeholders)}

      :else nil)))

(defn run-command!
  "Executes a prepared command (with ::strategy and ::value).
  Sets up the app state to run in foreground and closes the TUI."
  [{::keys [value strategy] :as cmd}]
  (case strategy
    ::and (doseq [command value]
            (u/shell-out command {:echo? true}))
    ::pipe (u/pipeline value {:inherit-last true})
    ::shell (u/shell-out value)))

(comment
  (def user-config- (prstack.config/read-local))
  (def glocal-config- (prstack.config/read-global))

  (prepare-command "git diff $from-sha..$to-sha | less"
    {:from-sha "abc123" :to-sha "def456"})

  (prepare-command [:pipe ["git" "diff" "$from-sha..$to-sha"] ["less" "-R"]]
    {:from-sha "abc123" :to-sha "def456"})

  (run-command!
    {::strategy ::shell
     ::value "ls" })

  (prepare-command "gh pr edit $pr-number --body \"$(git diff $from-sha..$to-sha | opencode describe)\""
    {:from-sha "abc" :to-sha "def" :pr-number 42}))
