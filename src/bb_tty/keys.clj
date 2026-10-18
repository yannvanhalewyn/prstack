(ns bb-tty.keys
  "Parses the bytes a terminal sends for key presses. Keys are keywords for
  special keys (e.g. `:up`, `:enter`, `:ctrl-c` or `:alt-b`), strings for
  characters (e.g. \"j\") and `{:paste text}` for pasted text, when bracketed
  paste is enabled. Unknown sequences are parsed as `:unknown`."
  (:require
    [clojure.string :as str]))

(def ^:private escape-sequences
  "Sequences following an escape. Arrow keys are sent differently depending on
  the terminal's cursor key mode, so both are included."
  {"[A" :up "OA" :up
   "[B" :down "OB" :down
   "[C" :right "OC" :right
   "[D" :left "OD" :left
   "[1;3C" :alt-right
   "[1;3D" :alt-left
   "[1;5C" :ctrl-right
   "[1;5D" :ctrl-left
   "[H" :home "OH" :home "[1~" :home "[7~" :home
   "[F" :end "OF" :end "[4~" :end "[8~" :end
   "[3~" :delete
   "[Z" :shift-tab})

(def ^:private paste-start "[200~")

(def ^:private paste-end
  "\\e[201~ as bytes."
  [27 91 50 48 49 126])

(defn- ascii [bytes]
  (apply str (map char bytes)))

(defn- decode [bytes]
  (String. (byte-array (map unchecked-byte bytes)) "UTF-8"))

(defn- index-where [pred coll]
  (first (keep-indexed #(when (pred %2) %1) coll)))

(defn- control-key
  "Ctrl+letter sends the letter's position in the alphabet, some of which are
  keys of their own."
  [b]
  (case (int b)
    9 :tab
    13 :enter
    (8 127) :backspace
    (if (<= 1 b 26)
      (keyword (str "ctrl-" (char (+ b 96))))
      :unknown)))

(defn- utf8-length [b]
  (cond
    (< b 0x80) 1
    (= 0xc0 (bit-and b 0xe0)) 2
    (= 0xe0 (bit-and b 0xf0)) 3
    (= 0xf0 (bit-and b 0xf8)) 4
    :else 1))

(defn- with-alt
  "Returns the key pressed together with alt, as alt sends an escape before
  the key. Nil for keys without an alt variant."
  [key]
  (cond
    (and (string? key) (re-matches #"[a-z]" key)) (keyword (str "alt-" key))
    (= :backspace key) :alt-backspace
    (= :left key) :alt-left
    (= :right key) :alt-right))

(defn- parse-paste [bytes complete?]
  (let [bytes (vec bytes)
        end (index-where #(= paste-end (subvec bytes % (+ % (count paste-end))))
              (range (inc (- (count bytes) (count paste-end)))))]
    (cond
      end [{:paste (str/replace (decode (subvec bytes 0 end)) #"\r\n?" "\n")}
           (subvec bytes (+ end (count paste-end)))]
      complete? [{:paste (str/replace (decode bytes) #"\r\n?" "\n")} []])))

(declare parse)

(defn- parse-escape
  "Parses the `bytes` following an escape."
  [bytes complete?]
  (let [bytes (vec bytes)
        incomplete #(when complete? [:esc bytes])]
    (if (empty? bytes)
      (incomplete)
      (case (int (first bytes))
        ;; CSI: \e[, parameters, ending with a byte in @..~
        91 (if-let [end (index-where #(<= 0x40 % 0x7e) (rest bytes))]
             (let [length (+ 2 end)
                   sequence (ascii (subvec bytes 0 length))
                   remaining (subvec bytes length)]
               (if (= paste-start sequence)
                 (parse-paste remaining complete?)
                 [(get escape-sequences sequence :unknown) remaining]))
             (incomplete))
        ;; SS3: \eO and one character
        79 (if (>= (count bytes) 2)
             [(get escape-sequences (ascii (subvec bytes 0 2)) :unknown) (subvec bytes 2)]
             (incomplete))
        ;; OSC: \e], ending with BEL or \e\, e.g. a late terminal reply
        93 (if-let [end (some #(when (or (= 7 (nth bytes %))
                                         (and (= 27 (nth bytes %))
                                              (= 92 (get bytes (inc %)))))
                                 %)
                          (range 1 (count bytes)))]
             [:unknown (subvec bytes (+ end (if (= 7 (nth bytes end)) 1 2)))]
             (incomplete))
        (if-let [[key remaining] (parse bytes complete?)]
          (if-let [alt-key (with-alt key)]
            [alt-key remaining]
            [:esc bytes])
          (incomplete))))))

(defn parse
  "Parses the first key from `bytes`, returning `[key remaining-bytes]`.
  Returns nil when the bytes could be the start of a longer sequence, unless
  `complete?` because no more bytes followed in time. Then a lone escape is
  the escape key."
  [bytes complete?]
  (when-let [[b & more] (seq bytes)]
    (cond
      (= 27 b) (parse-escape more complete?)
      (or (< b 32) (= 127 b)) [(control-key b) (vec more)]
      :else (let [n (utf8-length b)]
              (cond
                (<= n (count bytes)) [(decode (take n bytes)) (vec (drop n bytes))]
                complete? [:unknown []])))))
