(ns inline-demo
  "Manual check of `bb-tty.inline`, run from the project root with:

    bb dev/inline_demo.clj               # inline
    bb dev/inline_demo.clj --fullscreen  # alternate screen

  Shows the detected background with the palette, the last key pressed and a
  list that grows and shrinks to exercise repainting. Resize the terminal while
  it runs, and check the record line after enter, esc and ctrl+c."
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.inline :as inline]
    [bb-tty.tty :as tty]
    [clojure.string :as str]))

(def spinner-frames ["⣾ " "⣽ " "⣻ " "⢿ " "⡿ " "⣟ " "⣯ " "⣷ "])

(defn- update-state [state key]
  (case key
    ("j" :down :ctrl-n) (update state :cursor #(mod (inc %) (count (:items state))))
    ("k" :up :ctrl-p) (update state :cursor #(mod (dec %) (count (:items state))))
    "+" (update state :items conj (str "Item " (inc (count (:items state)))))
    "-" (if (> (count (:items state)) 1)
          (-> state
            (update :items pop)
            (update :cursor #(min % (- (count (:items state)) 2))))
          state)
    :enter (inline/submit state (nth (:items state) (:cursor state)))
    :esc (inline/skip state)
    (assoc state :last-key key)))

(defn- view [{:keys [items cursor frame last-key]} {:keys [rows cols]}]
  (let [p (ansi/palette (tty/background))
        s (fn [k text] (ansi/style (p k) text))]
    (concat
      [(str (s :accent (nth spinner-frames frame)) (s :prompt "Inline renderer demo"))
       (s :muted (format "background %s, terminal %dx%d, last key %s"
                   (name (tty/background)) cols rows (pr-str last-key)))
       ""
       (str (s :prompt "Palette") " "
            (s :button-selected "  Yes  ") " "
            (s :button-unselected "  No  ") " "
            (s :dot-active "•") (s :dot-inactive "••"))]
      (for [[i item] (map-indexed vector items)]
        (if (= i cursor)
          (s :accent (str "> " item))
          (str "  " item)))
      [""
       (str/join (s :help-separator " • ")
         (for [[k desc] [["↓↑" "navigate"] ["+/-" "add/remove"]
                         ["enter" "submit"] ["esc" "skip"] ["ctrl+c" "abort"]]]
           (str (s :help-key k) " " (s :help-desc desc))))])))

(defn- record [{:keys [items cursor]} outcome]
  (let [p (ansi/palette (tty/background))]
    (case outcome
      :submitted (str (ansi/style (:accent p) "✔") " Picked "
                      (ansi/style (:accent p) (nth items cursor)))
      :skipped (str (ansi/style (:muted p) "✗") " Picked " (ansi/style (:muted p) "skipped"))
      :aborted (str (ansi/style (:muted p) "✗") " Picked " (ansi/style (:muted p) "aborted")))))

(defn -main [& args]
  (println "Output before the prompt stays in place.")
  (try
    (let [result (inline/run!
                   {:init {:items ["Item 1" "Item 2" "Item 3"] :cursor 0 :frame 0}
                    :update update-state
                    :view view
                    :record record
                    :tick {:ms 100 :f #(update % :frame (fn [i] (mod (inc i) (count spinner-frames))))}
                    :fullscreen? (some #{"--fullscreen"} args)})]
      (println "Returned" (pr-str result)))
    (catch clojure.lang.ExceptionInfo e
      (if (= ::inline/interrupted (:type (ex-data e)))
        (println "Interrupted")
        (throw e)))))

(apply -main *command-line-args*)
