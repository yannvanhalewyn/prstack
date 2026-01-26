(ns bb-tty.ansi
  (:require
    [clojure.string :as str]))

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
   :red "\033[31m"
   :white "\033[37m"
   :gray "\033[90m"
   :orange "\033[38;5;208m"        ; 256-color true orange
   :bright-orange "\033[38;5;214m"  ; 256-color bright orange
   :bright-yellow "\033[93m"        ; Standard ANSI bright yellow (orange-ish)
   :bg-light-gray "\033[47m"
   :bg-gray "\033[100m"
   :bg-blue "\033[44m"})

(def colorize
  (if (System/getenv "NO_COLORS")
    (fn [_ text]
      text)
    (fn [color-keys text]
      (let [color-codes (str/join "" (map colors (cond-> color-keys keyword? vector)))]
        (str color-codes text (colors :reset))))))

(defn strip-ansi
  "Removes all ANSI escape codes from a string to get the visual length."
  [s]
  (str/replace s #"\u001b\[[0-9;]*[a-zA-Z]" ""))
