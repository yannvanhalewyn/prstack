(ns prstack.app
  (:require
    [clojure.tools.logging :as log]
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.tty :as tty]
    [prstack.vcs :as vcs]))

(defonce app-state
  (atom {:selected-lang 0}))

(def langs
  ["Clojure" "Java" "Python" "Ruby" "Rust"])

(defn select-component [_state]
  (tty/component
    {:on-key-press
     (fn [key]
       (condp = key
         ;; Arrow keys are actually the following sequence
         ;; 27 91 68 (map char [27 91 68])
         ;; So need to keep a stack of recent keys to check for up/down
         (int \j) (swap! app-state update :selected-lang #(min (dec (count langs)) (inc %)))
         (int \k) (swap! app-state update :selected-lang #(max 0 (dec %)))
         tty/UP_KEY (swap! app-state update :selected-lang #(max 0 (dec %)))
         tty/DOWN_KEY (swap! app-state update :selected-lang #(min (dec (count langs)) (inc %)))
         tty/RETURN_KEY (tty/close!
                          {:after-close
                           (fn []
                             (log/debug "Awaited closing")
                             (println "Picked:" (nth langs (:selected-lang @app-state))))})
         nil))}
    (fn [state]
      (for [[idx item] (map-indexed vector langs)]
        (if (= idx (:selected-lang state))
          (str (tty/bolden (tty/green "▊" #_"▶")) item)
          (str " " item))))))

(defn- format-change
  "Formats the bookmark as part of a stack at the given index"
  [i change]
  (let [indent (str (apply str (repeat (* (dec i) 2) " ")) (when-not (zero? i) "└─ "))]
    (str (tty/colorize :yellow indent)
         (tty/colorize :blue (vcs/local-branchname change)))))

(defn- render-stacks [{:keys [stacks vcs-config include-prs?]}]
  (if (empty? stacks)
    (tty/colorize :cyan "No stacks detetected")
    (let [max-width
          (when-let [counts
                     (seq
                       (mapcat #(map count (map-indexed format-change %))
                         stacks))]
            (apply max counts))]
      (for [stack stacks
            :let [formatted-bookmarks (map-indexed format-change stack)]
            [i [change formatted-bookmark]]
            (map-indexed vector
              (map vector stack formatted-bookmarks))]
        (if include-prs?
          (let [head-branch (vcs/local-branchname change)
                pr-url (when-let [base-branch (vcs/local-branchname (get stack (dec i)))]
                         (vcs/find-pr head-branch base-branch))
                padded-bookmark (format (str "%-" max-width "s") formatted-bookmark)]
            (str padded-bookmark " "
                 (cond
                   pr-url
                   (str (tty/colorize :green "✔") " PR Found"
                        (tty/colorize :gray (str " (" pr-url ")")))
                   ;; Show if 'needs push'
                   (not= head-branch (:vcs-config/trunk-bookmark vcs-config))
                   (str (tty/colorize :red "X") " No PR Found")
                   :else "")))
          formatted-bookmark)))))

(defn run! []
  (let [config (config/read-local)
        vcs-config (vcs/config)
        stacks (stack/get-current-stacks vcs-config)]
    (tty/run-ui!
      (tty/render! app-state
        (tty/component
          {:on-key-press #(if (= % (int \q))
                            (tty/close!)
                            false)}
          (fn [state]
            (render-stacks
              {:stacks stacks
               :vcs-config vcs-config
               :include-prs? true})))))))
