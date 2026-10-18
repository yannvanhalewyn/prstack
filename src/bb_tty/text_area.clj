(ns bb-tty.text-area
  "A multi-line text editor, modeled on the textarea of bubbles that gum uses.
  Edits within a line are those of `bb-tty.text-input`, lines wrap at the
  width and the view scrolls to keep the cursor in view."
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.text-input :as text-input]
    [clojure.string :as str]))

(defn init [value]
  (let [lines (str/split (or value "") #"\n" -1)]
    {:lines lines
     :row (dec (count lines))
     :col (count (peek lines))
     :offset 0
     :blink-on? true}))

(defn value [area]
  (str/join "\n" (:lines area)))

(defn- insert-text
  "Inserts `text` at the cursor, which can span multiple lines."
  [{:keys [lines row col] :as area} text]
  (let [line (nth lines row)
        before (subs line 0 col)
        after (subs line col)
        parts (str/split text #"\n" -1)
        n (count parts)]
    (assoc area
      :lines (vec (concat (subvec lines 0 row)
                    (if (= 1 n)
                      [(str before (first parts) after)]
                      (concat [(str before (first parts))]
                        (subvec parts 1 (dec n))
                        [(str (peek parts) after)]))
                    (subvec lines (inc row))))
      :row (+ row (dec n))
      :col (if (= 1 n) (+ col (count (first parts))) (count (peek parts)))
      :blink-on? true)))

(defn- join-lines
  "Joins the line at `row` with the next one."
  [{:keys [lines] :as area} row]
  (assoc area
    :lines (vec (concat (subvec lines 0 row)
                  [(str (nth lines row) (nth lines (inc row)))]
                  (subvec lines (+ row 2))))
    :row row
    :col (count (nth lines row))
    :blink-on? true))

(defn handle-key
  "Returns the text area after handling `key`, or nil when it isn't an editing
  key. Ctrl+j inserts a new line."
  [{:keys [lines row col] :as area} key]
  (let [line (nth lines row)
        first-line? (zero? row)
        last-line? (= row (dec (count lines)))
        move #(assoc area :row %1 :col %2 :blink-on? true)]
    (cond
      (:paste key) (insert-text area (:paste key))
      (= :ctrl-j key) (insert-text area "\n")

      (and (#{:up :ctrl-p} key) (not first-line?))
      (move (dec row) (min col (count (nth lines (dec row)))))

      (and (#{:down :ctrl-n} key) (not last-line?))
      (move (inc row) (min col (count (nth lines (inc row)))))

      (#{:up :ctrl-p :down :ctrl-n} key) area

      (and (#{:left :ctrl-b} key) (zero? col) (not first-line?))
      (move (dec row) (count (nth lines (dec row))))

      (and (#{:right :ctrl-f} key) (= col (count line)) (not last-line?))
      (move (inc row) 0)

      (and (= :backspace key) (zero? col) (not first-line?))
      (join-lines area (dec row))

      (and (#{:delete :ctrl-d} key) (= col (count line)) (not last-line?))
      (join-lines area row)

      :else
      (when-let [{:keys [value pos]} (text-input/handle-key {:value line :pos col} key)]
        (assoc area
          :lines (assoc lines row value)
          :col pos
          :blink-on? true)))))

(defn blink
  "Toggles the cursor, call every `text-input/blink-ms`."
  [area]
  (update area :blink-on? not))

(defn- wrap
  "Splits `line` into rows of `width`. The line with the cursor at its end
  gets an empty row when it fills the last row, to fit the cursor."
  [line width cursor-at-end?]
  (let [rows (mapv #(apply str %) (partition-all width line))]
    (if (or (empty? rows)
            (and cursor-at-end? (zero? (mod (count line) width))))
      (conj rows "")
      rows)))

(defn- visual-rows
  "Returns the rows of the wrapped lines as `{:text :cursor}`, where cursor is
  the column of the cursor when it's on that row."
  [{:keys [lines row col]} width]
  (vec (mapcat (fn [i line]
                 (let [cursor-line? (= i row)
                       cursor-row (quot col width)]
                   (map-indexed (fn [j text]
                                  {:text text
                                   :cursor (when (and cursor-line? (= j cursor-row))
                                             (- col (* j width)))})
                     (wrap line width (and cursor-line? (= col (count line)))))))
         (range) lines)))

(defn- scroll-offset
  "Returns the offset of the first visible row, scrolling as little as
  possible from `offset` to show the cursor's row."
  [offset cursor-row height]
  (-> offset
    (min cursor-row)
    (max (- cursor-row (dec height)))))

(defn- text-width [width prompt]
  (max 1 (- width (ansi/visual-length prompt) 1)))

(defn scroll
  "Scrolls to keep the cursor in view of `height` rows, call after every
  change."
  [area {:keys [width height prompt]}]
  (let [rows (visual-rows area (text-width width prompt))
        cursor-row (first (keep-indexed #(when (:cursor %2) %1) rows))]
    (update area :offset scroll-offset cursor-row height)))

(defn view
  "Renders `height` rows of the text area, each starting with `prompt`. Shows
  `placeholder` while it's empty. The cursor is the character under it in
  reverse `cursor-style`."
  [{:keys [blink-on? offset] :as area}
   {:keys [prompt placeholder width height cursor-style placeholder-style]}]
  (let [cursor (fn [ch]
                 (if blink-on?
                   (ansi/style (assoc cursor-style :reverse true) ch)
                   ch))
        rows (visual-rows area (text-width width prompt))
        cursor-row (first (keep-indexed #(when (:cursor %2) %1) rows))
        offset (scroll-offset offset cursor-row height)
        visible (->> rows (drop offset) (take height))]
    (if (and (= "" (value area)) (seq placeholder))
      (cons (str prompt
                 (cursor (if blink-on?
                           (subs placeholder 0 1)
                           (ansi/style placeholder-style (subs placeholder 0 1))))
                 (ansi/style placeholder-style (subs placeholder 1)))
        (repeat (dec height) prompt))
      (concat
        (for [{:keys [text] col :cursor} visible]
          (str prompt
               (if col
                 (str (subs text 0 col)
                      (cursor (if (< col (count text)) (subs text col (inc col)) " "))
                      (when (< (inc col) (count text)) (subs text (inc col))))
                 text)))
        (repeat (- height (count visible)) prompt)))))
