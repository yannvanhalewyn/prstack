(ns prstack.app
  (:require
    [clojure.tools.logging :as log]
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.tty :as tty]
    [prstack.vcs :as vcs]))

(defonce app-state
  (atom {:selected-lang 0
         ::prs {}}))

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
  [vcs-config change]
  (let [branchname (vcs/local-branchname change)]
    (str
      (if (= branchname (:vcs-config/trunk-bookmark vcs-config))
        " \ue729 " #_"└─" #_" \ueb06 "  #_" \ueafc "
        " \ue0a0 ")
      (vcs/local-branchname change))))

(defmulti dispatch! (fn [[evt]] evt))

(defmethod dispatch! :event/fetch-pr
  [[_ head-branch base-branch :as evt]]
  (when-not (get-in @app-state [::prs head-branch base-branch])
    (future
      (swap! app-state update ::prs assoc-in [head-branch base-branch]
        {:pr/url
         (vcs/find-pr head-branch base-branch)}))))

(defn- render-stacks
  [{:keys [stacks vcs-config] ::keys [prs]}]
  (if (empty? stacks)
    (tty/colorize :cyan "No stacks detetected")
    (let [max-width
          (when-let [counts
                     (seq
                       (mapcat #(map count (map (partial format-change vcs-config) %))
                         stacks))]
            (apply max counts))]
      [(tty/colorize :cyan (str "\uf51e " "Stack"))
       (for [stack stacks
             [i [change formatted-bookmark]]
             (->> (reverse stack)
               (map (partial format-change vcs-config))
               (map vector (reverse stack))
               (map-indexed vector))]
         (let [head-branch (vcs/local-branchname change)
               base-branch (vcs/local-branchname (get (vec (reverse stack)) (inc i)))
               _ (spit "target/dev.log" (str "Head branch: " head-branch "\n" "Base branch:" base-branch "\n") :append true)
               pr-info (when base-branch
                         (dispatch! [:event/fetch-pr head-branch base-branch])
                         (or (get-in prs [head-branch base-branch])
                             {:http/status :pending}))
               padded-bookmark (format (str "%-" max-width "s") formatted-bookmark)]
           (str padded-bookmark " "
                (cond
                  (= (:http/status pr-info) :pending)
                  (tty/colorize :gray "Fetching...")

                  (:pr/url pr-info)
                  (str (tty/colorize :green "✔") " PR Found"
                       (tty/colorize :gray (str " (" (:pr/url pr-info) ")")))
                  ;; TODO Show if 'needs push'
                  (contains? pr-info :pr/url)
                  (str (tty/colorize :red "X") " No PR Found")

                  :else ""))))])))

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
               ::prs (::prs state)
               :vcs-config vcs-config
               :include-prs? true})))))))
