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
(def CLEAR_TO_EOL "\u001b[K")
(def CLEAR_TO_END "\u001b[J")
;; Terminals that support synchronized output draw everything in between at
;; once, avoiding flicker on repaints. Others ignore it.
(def SYNC_START "\u001b[?2026h")
(def SYNC_END "\u001b[?2026l")

(defn cursor-up [n]
  (if (pos? n)
    (str "\u001b[" n "A")
    ""))

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

(def ^:private no-colors?
  (some? (System/getenv "NO_COLORS")))

(def colorize
  (if no-colors?
    (fn [_ text]
      text)
    (fn [color-keys text]
      (let [color-codes (str/join "" (map colors (cond-> color-keys (keyword? color-keys) vector)))]
        (str color-codes text (colors :reset))))))

(defn- color-params
  "Returns the SGR parameters setting a 256-color index or a \"#rrggbb\" color,
  `base` is 38 for the foreground and 48 for the background."
  [base color]
  (if (string? color)
    (let [[r g b] (map #(Integer/parseInt % 16) (re-seq #"\w\w" (subs color 1)))]
      (str base ";2;" r ";" g ";" b))
    (str base ";5;" color)))

(defn style
  "Styles `text` with `:fg` and `:bg` colors, each a 256-color index or a
  \"#rrggbb\" string, and `:bold`. Parts of `text` that are styled already
  keep their style, the rest gets this one."
  [{:keys [fg bg bold]} text]
  (let [params (cond-> []
                 bold (conj "1")
                 fg (conj (color-params 38 fg))
                 bg (conj (color-params 48 bg)))]
    (if (or no-colors? (empty? params))
      text
      (let [start (str "\u001b[" (str/join ";" params) "m")
            reset (colors :reset)]
        ;; Restarts the style after every reset of an inner style
        (str start (str/replace text reset (str reset start)) reset)))))

(defn palette
  "Returns the styles of prompts, taken from gum, for a terminal with a `:light`
  or `:dark` background. See `style`."
  [background]
  (let [light? (= :light background)
        pick (fn [light dark] (if light? light dark))]
    {:accent {:fg 212}
     ;; The terminal's default foreground, instead of gum's purple, as
     ;; prstack overrode it while using gum
     :prompt {}
     :muted {:fg 240}
     :button-selected {:fg 230 :bg 212}
     :button-unselected (pick {:fg 235 :bg 254} {:fg 254 :bg 235})
     :help-key {:fg (pick "#909090" "#626262")}
     :help-desc {:fg (pick "#B2B2B2" "#4A4A4A")}
     :help-separator {:fg (pick "#DADADA" "#3C3C3C")}
     :dot-active {:fg (pick "#847A85" "#979797")}
     :dot-inactive {:fg (pick "#DDDADA" "#3C3C3C")}}))

(defn parse-background-reply
  "Returns `:light` or `:dark` from a terminal's reply to the OSC 11 query for
  its background color, e.g. \"\\u001b]11;rgb:1e1e/1e1e/2e2e\\u0007\". Returns
  nil when the reply has no color."
  [reply]
  (when-let [[_ & channels] (re-find #"\]11;rgba?:(\p{XDigit}+)/(\p{XDigit}+)/(\p{XDigit}+)" reply)]
    (let [[r g b] (for [hex channels]
                    ;; Channels have 1 to 4 hex digits, scale them to 0..1
                    (/ (Long/parseLong hex 16)
                      (dec (Math/pow 16 (count hex)))))
          luminance (+ (* 0.2126 r) (* 0.7152 g) (* 0.0722 b))]
      (if (< luminance 0.5) :dark :light))))

(defn parse-colorfgbg
  "Returns `:light` or `:dark` from the COLORFGBG environment variable some
  terminals set, e.g. \"15;0\" for white on black. Returns nil when unset."
  [s]
  (when-let [bg (some-> s (str/split #";") last parse-long)]
    (if (or (<= 0 bg 6) (= 8 bg)) :dark :light)))

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
