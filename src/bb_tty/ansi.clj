(ns bb-tty.ansi
  (:require
    [clojure.string :as str]
    [prstack.utils :as u]))

;; Terminal control constants
(def CLEAR_SCREEN "\u001b[2J")
(def CURSOR_HOME "\u001b[H")
(def HIDE_CURSOR "\u001b[?25l")
(def SHOW_CURSOR "\u001b[?25h")
(def ALT_SCREEN "\u001b[?1049h")
(def NORMAL_SCREEN "\u001b[?1049l")
(def CURSOR_UP "\u001b[1A")
(def CLEAR_LINE "\u001b[2K")

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
   :bg-light-gray "\033[47m"
   :bg-gray "\033[100m"
   :bg-blue "\033[44m"})

(def colorize
  (if (System/getenv "NO_COLORS")
    (fn [_ text]
      text)
    (fn [color-keys text]
      (let [color-codes (str/join "" (map colors (u/vectorize color-keys)))]
        (str color-codes text (colors :reset))))))
