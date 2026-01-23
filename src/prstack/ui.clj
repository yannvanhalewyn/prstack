(ns prstack.ui
  "Shared UI formatting utilities for CLI and TUI."
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [prstack.vcs :as vcs]))

(defn format-change
  "Formats a branch name with appropriate icon and color based on bookmark type.

  Type mappings:
    :trunk         -> blue diamond (◆)
    :feature-base  -> orange fisheye (◉)
    :regular       -> default color git branch icon (\ue0a0)

  Options:
    :trunk? - Override to force trunk styling (for backwards compatibility)
    :no-color? - If true, returns uncolored text (useful for applying background colors)"
  ([vcs change]
   (format-change vcs change {}))
  ([vcs change {:keys [trunk? no-color?]}]
   (let [bookmark-type (if trunk?
                         :trunk
                         (:change/bookmark-type change))
         branch-name (vcs/local-branchname vcs change)
         [icon color] (case bookmark-type
                        :trunk        ["◆" :blue]
                        :feature-base ["◉" :bright-yellow]
                        :regular      ["\ue0a0" :default]
                        ["\ue0a0" :default])]
     (if no-color?
       (str " " icon " " branch-name)
       (str " " (ansi/colorize color icon) " "
            (ansi/colorize color branch-name))))))

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
