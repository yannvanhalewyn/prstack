(ns bb-tty.fuzzy
  "Fuzzy matching ported from sahilm/fuzzy, which gum's filter uses. Matches
  are ranked by bonuses for matching the first character, characters after
  separators or camelCase humps and adjacent characters.")

(def ^:private first-char-match-bonus 10)
(def ^:private match-following-separator-bonus 20)
(def ^:private camel-case-match-bonus 20)
(def ^:private adjacent-match-bonus 5)
(def ^:private unmatched-leading-char-penalty -5)
(def ^:private max-unmatched-leading-char-penalty -15)

(def ^:private separators (set "/-_ .\\"))

(defn- same-char?
  "Compares characters ignoring case, nil (no character) matches nothing."
  [a b]
  (and (some? a) (some? b)
       (= (Character/toLowerCase (char a)) (Character/toLowerCase (char b)))))

(defn match
  "Returns `{:score :indexes}` when the characters of `pattern` appear in `s`
  in order, ignoring case, or nil when they don't. The indexes are those of the
  matched characters in `s`. A higher score is a better match."
  [^String pattern ^String s]
  (when (seq pattern)
    (let [n (count s)
          pattern-length (count pattern)]
      (loop [j 0
             pattern-index 0
             indexes []
             total 0
             best-score -1
             best-index -1
             adjacent-bonus 0
             last-char nil
             last-index 0]
        (if (= j n)
          (when (= pattern-length (count indexes))
            ;; Penalty for every unmatched character
            {:score (+ total (- (count indexes) n))
             :indexes indexes})
          (let [c (.charAt s j)
                match? (and (< pattern-index pattern-length)
                            (same-char? c (.charAt pattern pattern-index)))
                ;; Adjacent matches keep increasing the bonus
                bonus (if (and match? (seq indexes) (= last-index (peek indexes)))
                        (+ (* 2 adjacent-bonus) adjacent-match-bonus)
                        0)
                score (when match?
                        (cond-> bonus
                          (zero? j)
                          (+ first-char-match-bonus)

                          (and last-char
                               (Character/isLowerCase (char last-char))
                               (Character/isUpperCase c))
                          (+ camel-case-match-bonus)

                          (and (pos? j) (contains? separators last-char))
                          (+ match-following-separator-bonus)))
                [best-score best-index] (if (and score (> score best-score))
                                          [score j]
                                          [best-score best-index])
                next-pattern-char (when (< pattern-index (dec pattern-length))
                                    (.charAt pattern (inc pattern-index)))
                next-char (when (< (inc j) n)
                            (.charAt s (inc j)))]
            ;; Settles on the best position for the current pattern character
            ;; when the next pattern character comes up, or at the end. This
            ;; finds the best match rather than the first, e.g. for "tk" in
            ;; "The Black Knight" it picks the K of Knight.
            (if (and (> best-index -1)
                     (or (nil? next-char)
                         (same-char? next-pattern-char next-char)))
              (let [best-score (if (empty? indexes)
                                 (+ best-score
                                    (max (* best-index unmatched-leading-char-penalty)
                                      max-unmatched-leading-char-penalty))
                                 best-score)]
                (recur (inc j) (inc pattern-index) (conj indexes best-index)
                  (+ total best-score) -1 -1 (+ adjacent-bonus bonus) c j))
              (recur (inc j) pattern-index indexes
                total best-score best-index (+ adjacent-bonus bonus) c j))))))))

(defn find-matches
  "Returns the strings matching `pattern` as `{:index :score :indexes}`, where
  index is the position in `strings`, best matches first. Equal scores keep
  their order."
  [pattern strings]
  (->> strings
    (keep-indexed (fn [i s]
                    (some-> (match pattern s) (assoc :index i))))
    (sort-by :score >)))
