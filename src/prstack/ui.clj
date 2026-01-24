(ns prstack.ui
  "Shared UI formatting utilities for CLI and TUI."
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]))

(defn format-change
  "Formats a branch name with appropriate icon and color based on bookmark type.

  Type mappings:
    :trunk         -> blue diamond (◆)
    :feature-base  -> orange fisheye (◉)
    :regular       -> default color git branch icon (\ue0a0)

  Options:
    :trunk? - Override to force trunk styling (for backwards compatibility)
    :no-color? - If true, returns uncolored text (useful for applying background colors)"
  ([change]
   (format-change change {}))
  ([change {:keys [no-color?]}]
   (let [branch-name (:change/selected-branchname change)
         [icon color] (case (:change/type change)
                        :trunk        ["◆" :blue]
                        :feature-base ["◉" :bright-yellow]
                        :regular      ["\ue0a0" :default]
                        ["\ue0a0" :default])]
     (if no-color?
       (str " " icon " " branch-name)
       (str " " (ansi/colorize color icon) " "
            (ansi/colorize color branch-name))))))

(defn format-pr-info
  "Formats PR information for display.

  Takes a pr-info-result tuple `[pr-info error]` and returns a formatted string
  with status indicator, PR number, and title.

  `pr-info` map which may contain:
    :http/status :status/pending - PR is being fetched
    :pr/url - PR URL (indicates PR exists)
    :pr/number - PR number
    :pr/title - PR title
    :pr/status - One of :pr.status/approved, :pr.status/changes-requested, :pr.status/review-required

  Returns a formatted string with status indicator, PR number, and title."
  [pr-info {:keys [error pending?]}]
  (cond
    (:pr/url pr-info)
    (str (case (:pr/status pr-info)
           :pr.status/approved (ansi/colorize :green "✓")
           :pr.status/changes-requested (ansi/colorize :red "✗")
           :pr.status/review-required (ansi/colorize :yellow "●")
           (ansi/colorize :gray "?"))
         " "
         (ansi/colorize :blue (str "#" (:pr/number pr-info)))
         " " (:pr/title pr-info))

    pending? (ansi/colorize :gray "Fetching...")
    error    (str (ansi/colorize :red "X") " Error: " error)
    :else    (ansi/colorize :gray "No PR found")))

(defn prompt-selection
  "Prompts the user to select from a list of options using gum.

  Args:
    options - A seq of strings to choose from
    opts - Optional map with keys:
      :prompt - prompt to display above the selection
      :limit - Maximum number of selections (default 1)

  Returns:
    Selected option(s) as a string (single selection) or seq of strings (multiple)"
  ([options]
   (prompt-selection options {}))
  ([options {:keys [prompt limit] :or {limit 1}}]
   (let [result (tty/prompt-filter
                  {:prompt prompt
                   :options options})]
     (if (= (:status result) 0)
       (if (= limit 1)
         (first (:result result))
         (:result result))
       nil))))
