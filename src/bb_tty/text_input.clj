(ns bb-tty.text-input
  "A single line text editor with readline keys, modeled on the textinput of
  bubbles that gum uses. It's a value with a cursor position, updated with keys
  from `tty/key-reader`."
  (:require
    [bb-tty.ansi :as ansi]))

(def blink-ms
  "How often the cursor blinks, as in bubbles."
  530)

(defn init [value]
  (let [value (or value "")]
    {:value value
     :pos (count value)
     :blink-on? true}))

(defn- space-at? [^String s i]
  (Character/isWhitespace (.charAt s i)))

(defn- word-start
  "Returns the start of the word before `pos`, skipping spaces before it."
  [s pos]
  (let [pos (loop [p pos]
              (if (and (pos? p) (space-at? s (dec p))) (recur (dec p)) p))]
    (loop [p pos]
      (if (and (pos? p) (not (space-at? s (dec p)))) (recur (dec p)) p))))

(defn- word-end
  "Returns the end of the word after `pos`, skipping spaces before it."
  [s pos]
  (let [n (count s)
        pos (loop [p pos]
              (if (and (< p n) (space-at? s p)) (recur (inc p)) p))]
    (loop [p pos]
      (if (and (< p n) (not (space-at? s p))) (recur (inc p)) p))))

(defn- printable? [key]
  (and (string? key)
       (seq key)
       (not (Character/isISOControl (.charAt ^String key 0)))))

(defn handle-key
  "Returns the input after handling `key`, or nil when it isn't an editing
  key. Any edit or movement shows the cursor, restarting the blink."
  [{:keys [value pos] :as input} key]
  (let [n (count value)
        move #(assoc input :pos % :blink-on? true)
        edit #(assoc input :value %1 :pos %2 :blink-on? true)]
    (if (printable? key)
      (edit (str (subs value 0 pos) key (subs value pos)) (+ pos (count key)))
      (case key
        (:left :ctrl-b) (move (max 0 (dec pos)))
        (:right :ctrl-f) (move (min n (inc pos)))
        (:alt-left :ctrl-left :alt-b) (move (word-start value pos))
        (:alt-right :ctrl-right :alt-f) (move (word-end value pos))
        (:home :ctrl-a) (move 0)
        (:end :ctrl-e) (move n)
        :backspace (if (pos? pos)
                     (edit (str (subs value 0 (dec pos)) (subs value pos)) (dec pos))
                     input)
        (:delete :ctrl-d) (edit (str (subs value 0 pos) (subs value (min n (inc pos)))) pos)
        (:ctrl-w :alt-backspace) (let [start (word-start value pos)]
                                   (edit (str (subs value 0 start) (subs value pos)) start))
        :alt-d (edit (str (subs value 0 pos) (subs value (word-end value pos))) pos)
        :ctrl-u (edit (subs value pos) 0)
        :ctrl-k (edit (subs value 0 pos) pos)
        nil))))

(defn blink
  "Toggles the cursor, call every `blink-ms`."
  [input]
  (update input :blink-on? not))

(defn view
  "Renders the input after `prompt`, showing `placeholder` while it's empty.
  When focused, the cursor is the character under it in reverse `cursor-style`.
  Text wider than `width` scrolls to keep the cursor in view."
  [{:keys [value pos blink-on?]}
   {:keys [prompt placeholder focused? width cursor-style placeholder-style]}]
  (let [cursor (fn [ch]
                 (if (and focused? blink-on?)
                   (ansi/style (assoc cursor-style :reverse true) ch)
                   ch))]
    (if (empty? value)
      (str prompt
           (if (seq placeholder)
             (str (cursor (if (and focused? blink-on?)
                            (subs placeholder 0 1)
                            (ansi/style placeholder-style (subs placeholder 0 1))))
                  (ansi/style placeholder-style (subs placeholder 1)))
             (cursor " ")))
      ;; One column for the cursor after the text
      (let [available (max 1 (- (or width 80) (ansi/visual-length prompt) 1))
            start (max 0 (- (inc pos) available))
            end (min (count value) (+ start available))]
        (str prompt
             (subs value start pos)
             (cursor (if (< pos (count value)) (subs value pos (inc pos)) " "))
             (when (< (inc pos) end) (subs value (inc pos) end)))))))
