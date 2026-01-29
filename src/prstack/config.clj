(ns prstack.config
  (:require
    [bb-tty.ansi :as ansi]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pp]
    [clojure.string :as str])
  (:import
    (java.io PushbackReader)))

(def UserConfig
  [:map
   [:vcs :keyword]
   [:ignored-branches :set :string]
   [:feature-base-branches :set :string]])

;; Command specification schema - supports multiple execution strategies
;; Examples:
;;   "git diff $from-sha..$to-sha"                    - Simple shell string
;;   ["git" "diff" "$from-sha..$to-sha"]              - Command vector
;;   [:shell "git diff $from-sha..$to-sha | less"]    - Explicit shell
;;   [:pipe ["git" "diff" "$from-sha..$to-sha"] ["less" "-R"]]  - Pipeline
(def CommandSpec
  [:or
   :string
   [:sequential [:string]]
   [:fn (fn [x]
          (and (sequential? x)
               (#{:shell :pipe} (first x))))]])

;; Custom command definition - extensible for future features
;; Currently supports:
;;   :cmd - Command specification (required)
;;
;; Future extensions could add:
;;   :confirm - Prompt for confirmation before running
;;   :prompt - Ask for additional user input
;;   :filter - Select from a list (fzf-like)
;;   :capture - Capture command output for chaining
(def CustomCommand
  [:or
   ;; Simple form: just a command spec
   CommandSpec
   ;; Extended form: map with :cmd and future options
   [:map
    ;; Future potential fields (not yet implemented):
    ;; [:confirm {:optional true} :boolean]
    ;; [:prompt {:optional true} [:map [:message :string]]]
    ;; [:filter {:optional true} [:map [:cmd CommandSpec]]]
    ;; [:capture {:optional true} :boolean]
    [:cmd CommandSpec]]])

;; Available placeholders for custom commands:
;;   $from-sha     - The commit SHA of the previous/base change
;;   $to-sha       - The commit SHA of the selected change
;;   $base-branch  - Alias for $from-branch
;;   $head-branch  - Alias for $to-branch
;;   $pr-number    - The PR number (if a PR exists for the selected change)
;;   $pr-url       - The PR URL (if a PR exists for the selected change)

(def GlobalConfig
  [:map
   ;; Legacy diffview-cmd for backwards compatibility
   [:diffview-cmd {:optional true} CommandSpec]
   ;; Custom commands mapped to keybindings
   ;; Keys are single-character strings like "d", "D", "x" etc.
   [:commands {:optional true}
    [:map-of :string CustomCommand]]])

(defn read-local []
  (let [config (let [file (io/file ".prstack/config.edn")]
                 (when (.exists file)
                   (with-open [rdr (io/reader file)]
                     (edn/read (PushbackReader. rdr)))))]
    (-> (or config {})
      (update :ignored-branches #(set (or % [])))
      (update :feature-base-branches #(set (or % []))))))

(defn write-local [config]
  (let [file (io/file ".prstack/config.edn")
        dir (.getParentFile file)]
    ;; Create .prstack directory if it doesn't exist
    (when-not (.exists dir)
      (.mkdirs dir))
    ;; Convert sets to vectors for EDN serialization
    (let [config-to-write (-> config
                            (update :ignored-branches set)
                            (update :feature-base-branches set))]
      (with-open [wtr (io/writer file)]
        (pp/pprint config-to-write wtr)))))

(defn read-global []
  (let [config-dir (io/file (System/getProperty "user.home") ".config" "prstack")
        config-file (io/file config-dir "config.edn")]
    (when (.exists config-file)
      (with-open [rdr (io/reader config-file)]
        (try
          (edn/read (PushbackReader. rdr))
          (catch Exception e
            (println (ansi/colorize :yellow "Warning:" )
              "Error reading EDN config file at ~/.config/prstack/config.edn:" (ex-message e))
            nil))))))

(defn- substitute-placeholders
  "Replaces placeholders in a command or string with actual values.
   Supports both $placeholder and :placeholder syntax."
  [cmd placeholders]
  (cond
    ;; Vector of command segments
    (vector? cmd)
    (mapv (fn [arg]
            (reduce (fn [s [placeholder value]]
                      (if value
                        (str/replace s placeholder value)
                        s))
                    arg
                    placeholders))
          cmd)

    ;; String (for :shell commands)
    (string? cmd)
    (reduce (fn [s [placeholder value]]
              (if value
                (str/replace s placeholder value)
                s))
            cmd
            placeholders)

    :else cmd))

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
    from-sha    (merge {":from-sha" from-sha "$from-sha" from-sha})
    to-sha      (merge {":to-sha" to-sha "$to-sha" to-sha})
    from-branch (merge {":from-branch" from-branch "$from-branch" from-branch
                        ;; Alias: base-branch = from-branch
                        ":base-branch" from-branch "$base-branch" from-branch})
    to-branch   (merge {":to-branch" to-branch "$to-branch" to-branch
                        ;; Alias: head-branch = to-branch
                        ":head-branch" to-branch "$head-branch" to-branch})
    pr-number   (merge {":pr-number" (str pr-number) "$pr-number" (str pr-number)})
    pr-url      (merge {":pr-url" pr-url "$pr-url" pr-url})))

(comment
  (substitute-placeholders ["git" "diff" "$from-sha..$to-sha"]
    (build-placeholders {:from-sha "abc123" :to-sha "def456"}))

  (build-placeholders {:from-sha "abc" :to-sha "def" :from-branch "main" :to-branch "feature"}))

(defn prepare-command
  "Prepares a command for execution by resolving placeholders and determining strategy.

  Returns a map with :strategy and :value keys:
    {:strategy :pipe|:shell, :value <prepared-command>}

  Command spec can be:
    - String: \"git diff $from-sha..$to-sha\" -> {:strategy :shell :value \"...\"}
    - Vector: [\"git\" \"diff\" ...] -> {:strategy :pipe :value [[\"git\" \"diff\" ...]]}
    - [:shell \"...\"] -> {:strategy :shell :value \"...\"}
    - [:pipe [...] [...]] -> {:strategy :pipe :value [[...] [...]]}
    - Map with :cmd key -> extracts :cmd and processes

  Context is a map that can include:
    :from-sha, :to-sha     - Commit SHAs
    :from-branch, :to-branch - Branch names
    :pr-number, :pr-url    - PR information"
  [cmd-spec context]
  (let [placeholders (build-placeholders context)
        ;; Handle map form (extended command definition)
        raw-spec (if (and (map? cmd-spec) (contains? cmd-spec :cmd))
                   (:cmd cmd-spec)
                   cmd-spec)]
    (cond
      ;; String - run as shell command (allows pipes, redirects, etc.)
      (string? raw-spec)
      {:strategy :shell
       :value (substitute-placeholders raw-spec placeholders)}

      ;; Keyword-based dispatch [:shell ...] or [:pipe ...]
      (and (sequential? raw-spec) (keyword? (first raw-spec)))
      (let [[strategy & args] raw-spec]
        (case strategy
          :pipe {:strategy :pipe
                 :value (mapv #(substitute-placeholders % placeholders) args)}
          :shell {:strategy :shell
                  :value (substitute-placeholders (first args) placeholders)}
          ;; Unknown strategy, treat as shell
          {:strategy :shell
           :value (substitute-placeholders (str/join " " raw-spec) placeholders)}))

      ;; Vector of strings - single command, run as pipeline
      (and (sequential? raw-spec) (seq raw-spec))
      {:strategy :pipe
       :value [(substitute-placeholders (vec raw-spec) placeholders)]}

      ;; Empty/nil
      :else nil)))

(defn get-diffview-cmd
  "Returns diffview command configuration with strategy.
  Returns a map with :strategy and :value keys.

  Supported formats in config:
  - [:pipe [\"git\" \"diff\" ...] [\"less\" \"-R\"]] - Pipeline of commands
  - [:shell \"git diff ... | less\"] - Shell command string
  - [\"git\" \"diff\" ...] - Single command (treated as 1-element pipeline)

  Placeholders: $from-sha, $to-sha, $from-branch, $to-branch, $pr-number, $pr-url"
  [global-config context]
  (let [default-cmd {:strategy :pipe
                     :value [["git" "--no-pager" "diff" "--color=always"
                              (str (:from-sha context) ".." (:to-sha context))]
                             [(or (System/getenv "PAGER") "less") "-R"]]}]
    (if-let [diffview-cmd (:diffview-cmd global-config)]
      (or (prepare-command diffview-cmd context) default-cmd)
      default-cmd)))

(defn get-custom-command
  "Gets a custom command by keybinding from config.
  Returns nil if no command is defined for the keybinding."
  [global-config keybinding context]
  (when-let [cmd-spec (get-in global-config [:commands keybinding])]
    (prepare-command cmd-spec context)))

(comment
  (read-local)
  (read-global)

  (prepare-command "git diff $from-sha..$to-sha | less"
    {:from-sha "abc123" :to-sha "def456"})

  (prepare-command [:pipe ["git" "diff" "$from-sha..$to-sha"] ["less" "-R"]]
    {:from-sha "abc123" :to-sha "def456"})

  (prepare-command {:cmd [:shell "gh pr edit $pr-number --body \"$(git diff $from-sha..$to-sha | opencode describe)\""]
                    :confirm true}
    {:from-sha "abc" :to-sha "def" :pr-number 42})

  (get-diffview-cmd (read-global)
    {:from-sha "abc123" :to-sha "def456"})

  (get-custom-command
    {:commands {"D" [:shell "git diff $from-sha..$to-sha | opencode -p 'describe these changes' | gh pr edit $pr-number --body-file -"]}}
    "D"
    {:from-sha "abc" :to-sha "def" :pr-number 42}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Example Configuration (~/.config/prstack/config.edn)
;;
;; This example shows current functionality and potential future extensions.
;;
;; CURRENT FUNCTIONALITY:
;; ----------------------
;;
;; {:diffview-cmd [:pipe ["git" "diff" "--color=always" "$from-sha..$to-sha"]
;;                       ["delta"]]
;;
;;  :commands
;;  {;; Override 'd' to use a different diff viewer
;;   "d" [:shell "git diff $from-sha..$to-sha | delta"]
;;
;;   ;; 'D' - Generate PR description using AI and update the PR
;;   "D" [:shell "git diff $from-sha..$to-sha | opencode -p 'Write a concise PR description for these changes' | gh pr edit $pr-number --body-file -"]
;;
;;   ;; 'e' - Edit the diff in your editor
;;   "e" [:pipe ["git" "diff" "$from-sha..$to-sha"] ["vim" "-"]]
;;
;;   ;; 'b' - Open branch in browser
;;   "b" [:shell "gh browse --branch $head-branch"]
;;
;;   ;; 'l' - View commit log between changes
;;   "l" [:pipe ["git" "log" "--oneline" "$from-sha..$to-sha"] ["less"]]
;;
;;   ;; 'f' - Show files changed
;;   "f" [:shell "git diff --stat $from-sha..$to-sha | less"]
;;
;;   ;; 'C' - Copy branch name to clipboard
;;   "C" [:shell "echo -n $head-branch | pbcopy && echo 'Copied $head-branch to clipboard'"]}}
;;
;;
;; FUTURE EXTENSIONS (not yet implemented):
;; ----------------------------------------
;;
;; The command system is designed to be extensible. Here's what future versions
;; might support:
;;
;; {:commands
;;  {;; Confirmation prompt before destructive actions
;;   "X" {:cmd [:shell "gh pr close $pr-number"]
;;        :confirm {:message "Are you sure you want to close PR #$pr-number?"}}
;;
;;   ;; User input prompts with placeholders
;;   "R" {:cmd [:shell "gh pr review $pr-number --$action"]
;;        :prompt {:var "$action"
;;                 :message "Review action:"
;;                 :options ["approve" "request-changes" "comment"]}}
;;
;;   ;; Free-form text input
;;   "T" {:cmd [:shell "gh pr edit $pr-number --title '$title'"]
;;        :prompt {:var "$title"
;;                 :message "New PR title:"
;;                 :default "$head-branch"}}
;;
;;   ;; Filter selection (fzf-like)
;;   "G" {:cmd [:shell "git checkout $selected"]
;;        :filter {:source [:shell "git branch --list"]
;;                 :var "$selected"
;;                 :message "Select branch:"}}
;;
;;   ;; Capture command output for chaining
;;   "A" {:cmd [:shell "gh pr view $pr-number --json author --jq .author.login"]
;;        :capture {:var "$author"
;;                  :then [:shell "echo 'Author: $author' | less"]}}
;;
;;   ;; Conditional execution based on context
;;   "P" {:when {:pr-exists true}
;;        :cmd [:shell "gh pr view $pr-number --web"]
;;        :else {:cmd [:shell "echo 'No PR exists for $head-branch'"]}}
;;
;;   ;; Multi-step workflows
;;   "W" {:steps [{:cmd [:shell "git diff $from-sha..$to-sha > /tmp/diff.patch"]}
;;                {:prompt {:var "$message" :message "Commit message:"}}
;;                {:cmd [:shell "git apply /tmp/diff.patch && git commit -m '$message'"]}]}}}
;;
;; The extended form uses a map with :cmd plus additional options.
;; Simple commands can still use the shorthand form (just the command spec).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
