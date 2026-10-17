(ns bb-tty.ansi
  (:require
    [clojure.string :as str])
  (:import
    (org.jline.utils AttributedString)))

;; Terminal control constants
(def CLEAR_SCREEN "\u001b[2J")
(def CURSOR_HOME "\u001b[H")
(def HIDE_CURSOR "\u001b[?25l")
(def SHOW_CURSOR "\u001b[?25h")
(def ALT_SCREEN "\u001b[?1049h")
(def NORMAL_SCREEN "\u001b[?1049l")
(def ^:lsp/allow-unused CURSOR_UP "\u001b[1A")
(def ^:lsp/allow-unused CLEAR_LINE "\u001b[2K")

(def colors
  {:reset "\033[0m"
   :bold "\033[1m"
   :green "\033[32m"
   :blue "\033[34m"
   :yellow "\033[33m"
   :cyan "\033[36m"
   :magenta "\033[35m"
   :red "\033[31m"
   :white "\033[37m"
   :bright-white "\033[97m"
   :gray "\033[90m"
   :orange "\033[38;5;208m"        ; 256-color true orange
   :bright-orange "\033[38;5;214m"  ; 256-color bright orange
   :bright-yellow "\033[93m"        ; Standard ANSI bright yellow (orange-ish)
   :bg-light-gray "\033[47m"
   :bg-gray "\033[100m"
   :bg-blue "\033[44m"
   :bg-dark-gray "\033[48;5;237m"})  ; 256-color, darker than :bg-gray

(def colorize
  (if (System/getenv "NO_COLORS")
    (fn [_ text]
      text)
    (fn [color-keys text]
      (let [color-codes (str/join "" (map colors (cond-> color-keys (keyword? color-keys) vector)))]
        (str color-codes text (colors :reset))))))

(defn strip-ansi
  "Removes all ANSI escape codes from a string to get the visual length."
  [s]
  (str/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))

(defn visual-length
  "Returns the visual column width of a string, stripping ANSI codes and
   accounting for wide characters (e.g., CJK characters, emoji)."
  [s]
  (.columnLength (AttributedString/fromAnsi s)))

(defn truncate
  "Truncates a string to at most `width` visual columns, ignoring ANSI codes
  in the width calculation and keeping the styling of the visible part."
  [s width]
  (let [as (AttributedString/fromAnsi s)]
    (if (> (.columnLength as) width)
      (.toAnsi (.columnSubSequence as 0 width))
      s)))
