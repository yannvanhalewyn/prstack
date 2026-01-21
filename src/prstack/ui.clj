(ns prstack.ui
  "Shared UI formatting utilities for CLI and TUI."
  (:require
    [bb-tty.ansi :as ansi]
    [prstack.vcs :as vcs]))

(defn format-change
  "Formats a branch name with appropriate icon and color based on bookmark type.

  Type mappings:
    :trunk         -> blue diamond (◆)
    :feature-base  -> orange fisheye (◉)
    :regular       -> default color git branch icon (\ue0a0)

  Options:
    :trunk? - Override to force trunk styling (for backwards compatibility)"
  ([vcs change]
   (format-change vcs change {}))
  ([vcs change {:keys [trunk?]}]
   (let [bookmark-type (if trunk?
                         :trunk
                         (:change/bookmark-type change))
         branch-name (vcs/local-branchname vcs change)
         [icon color] (case bookmark-type
                        :trunk        ["◆" :blue]
                        :feature-base ["◉" :bright-yellow]
                        :regular      ["\ue0a0" :default]
                        ["\ue0a0" :default])]
     (str " " (ansi/colorize color icon) " "
          (ansi/colorize color branch-name)))))
