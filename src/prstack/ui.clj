(ns prstack.ui
  "Shared UI formatting utilities for CLI and TUI."
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [prstack.github :as github]))

(defn format-branch [name]
  (ansi/colorize :cyan name))

(defn info [& parts]
  (println (str "  " (apply str parts))))

(defn success [msg]
  (println (str "  " (ansi/colorize :green "✓") " " msg)))

(defn format-change
  "Formats a branch name with appropriate icon and color based on bookmark type.

  Type mappings:
    :trunk         -> blue diamond (◆)
    :feature-base  -> orange fisheye (◉)
    :regular       -> default color git branch icon (\ue0a0)"
  [change]
  (let [branch-name (:change/selected-branchname change)
        [icon color] (case (:change/type change)
                       :trunk        ["◆" :blue]
                       :feature-base ["◉" :bright-yellow]
                       :regular      ["\ue0a0" :default]
                       ["\ue0a0" :default])]
    (ansi/colorize color (str icon " " branch-name))))

(defn format-pr-info
  "Formats PR information for display.

  Takes a PR map and returns a formatted string with review status indicator, PR
  number, and title.

  `pr-info` map which may contain:
    :pr/url - PR URL (indicates PR exists)
    :pr/number - PR number
    :pr/title - PR title
    :pr/status - One of :pr.status/approved, :pr.status/changes-requested, :pr.status/review-required

  Options:
    :error - Error message to display
    :pending? - If true, displays a message indicating the PR is being fetched
    :wrong-base-branch - If set, indicates the PR has the wrong base branch (expected value)

  Returns a formatted string with status indicator, PR number, and title."
  [pr-info {:keys [error pending? _head-change base-change wrong-base-branch]}]
  (cond
    (:pr/url pr-info)
    (str (case (:pr/status pr-info)
           :pr.status/approved (ansi/colorize :green "✓")
           :pr.status/changes-requested (ansi/colorize :red "✗")
           :pr.status/review-required (ansi/colorize :yellow "●")
           (ansi/colorize :gray "?"))
         " "
         (ansi/colorize :blue (str "#" (:pr/number pr-info)))
         " " (:pr/title pr-info)
         (when wrong-base-branch
           (str "  "
                (ansi/colorize :red "⚠ Wrong base: ")
                (format-change base-change))))

    pending? (ansi/colorize :gray "  Fetching...")
    error    (str (ansi/colorize :red "X") " " error)
    :else    (ansi/colorize :gray "  No PR found")))

(defn fetch-prs-with-spinner []
  (let [res (tty/with-spinner (github/list-prs-cmd)
              {:title "Fetching PRs..."})]
    (if (= (:status res) 0)
      [(github/parse-prs-cmd-output (first (:result res)))]
      [nil {:error/type :remoterepo/error-fetching-prs
            :error/message "Error fetching PRs..."}])))
