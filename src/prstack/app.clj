(ns prstack.app
  (:require
    [clojure.java.browse :as browse]
    [clojure.string :as str]
    [prstack.commands.sync :as commands.sync]
    [prstack.config :as config]
    [prstack.stack :as stack]
    [prstack.tty :as tty]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defonce app-state
  (atom {::stack-selection-idx 0
         ::prs {}}))

(defn- format-change
  "Formats the bookmark as part of a stack at the given index"
  [{:keys [vcs-config change]}]
  (let [branchname (vcs/local-branchname change) ]
    (str
      (if (= branchname (:vcs-config/trunk-bookmark vcs-config))
        " \ue729 " #_"└─" #_" \ueb06 "  #_" \ueafc "
        " \ue0a0 ")
      (vcs/local-branchname change))))

(defmulti dispatch! (fn [[evt]] evt))

(defmethod dispatch! :event/fetch-pr
  [[_ head-branch base-branch]]
  (when-not (get-in @app-state [::prs head-branch base-branch])
    (future
      (swap! app-state update ::prs assoc-in [head-branch base-branch]
        {:pr/url
         (vcs/find-pr head-branch base-branch)}))))

(defmethod dispatch! :event/run-diff
  [[_ from-sha to-sha]]
  (tty/close!
    {:after-close
     #(u/shell-out ["/Users/yannvanhalewyn/repos/nvim-macos-x86_64/bin/nvim" "-c"
                    (str "DiffviewOpen " from-sha "..." to-sha)])}))

(defn- render-stacks
  [{:keys [stacks vcs-config] ::keys [prs]}]
  (if (empty? stacks)
    (tty/colorize :cyan "No stacks detetected")
    (tty/component
      {:on-key-press
       (fn [key]
         (let [state* @app-state
               flatstack (apply concat (::stacks state*))]
           (condp = key
             ;; Arrow keys are actually the following sequence
             ;; 27 91 68 (map char [27 91 68])
             ;; So need to keep a stack of recent keys to check for up/down
             (int \j) (swap! app-state update ::stack-selection-idx
                        #(min (dec (count flatstack)) (inc %)))
             (int \k) (swap! app-state update ::stack-selection-idx
                        #(max 0 (dec %)))
             (int \d)
             (when-not (>= (::stack-selection-idx state*) (count flatstack))
               (let [selected-change (nth flatstack (::stack-selection-idx state*))
                     prev-change (nth flatstack (inc (::stack-selection-idx state*)))]
                 (dispatch! [:event/run-diff
                             (or
                               (:change/commit-sha prev-change)
                               (vcs/local-branchname prev-change))
                             (:change/commit-sha selected-change)])))
             (int \o)
             (let [selected-change (nth flatstack (::stack-selection-idx state*))
                   head-branch (vcs/local-branchname selected-change)
                   base-branch (vcs/local-branchname (nth flatstack (inc (::stack-selection-idx state*))))]
               (when-let [url (get-in state* [::prs head-branch base-branch :pr/url])]
                 (browse/browse-url url)))
             (int \s)
             (tty/close!
               {:after-close
                #((:exec commands.sync/command) [])})
             nil)))}
      (fn [state]
        (let [max-width
              (when-let [counts
                         (seq
                           (mapcat (fn [stack]
                                     (->> stack
                                       (map #(format-change
                                               {:change %
                                                :vcs-config vcs-config}))
                                       (map count)))
                             stacks))]
                (apply max counts))]
          [(tty/colorize :cyan (str "\uf51e " "Stack"))
           (for [stack stacks
                 [i [change formatted-bookmark]]
                 (->> stack
                   (map #(format-change {:change % :vcs-config vcs-config}))
                   (map vector stack)
                   (map-indexed vector))]
             (let [head-branch (vcs/local-branchname change)
                   base-branch (vcs/local-branchname (get stack (inc i)))
                   pr-info (when base-branch
                             (dispatch! [:event/fetch-pr head-branch base-branch])
                             (or (get-in prs [head-branch base-branch])
                                 {:http/status :pending}))
                   padded-bookmark (format (str "%-" max-width "s") formatted-bookmark)]
               (str (if (= i (::stack-selection-idx state))
                      (tty/colorize :bg-gray padded-bookmark)
                      padded-bookmark)
                    " "
                    (cond
                      (= (:http/status pr-info) :pending)
                      (tty/colorize :gray "Fetching...")

                      (:pr/url pr-info)
                      (str (tty/colorize :green "✔") " PR Found"
                           (tty/colorize :gray (str " (" (:pr/url pr-info) ")")))
                      ;; TODO Show if 'needs push'
                      (contains? pr-info :pr/url)
                      (str (tty/colorize :red "X") " No PR Found")

                      :else ""))))])))))

(defn- render-keybindings []
  (let [{:keys [cols]} (tty/get-terminal-size)
        separator (str/join (repeat (or cols 80) "\u2500"))
        keybindings ["j/k: Navigate" "d: Diff" "o: Open PR" "q: Quit"]]
    [""
     (tty/colorize :gray separator)
     (tty/colorize :gray (str/join "  " keybindings))]))

(defn run! []
  (let [config (config/read-local)
        vcs-config (vcs/config)
        stacks (mapv (comp vec reverse) (stack/get-current-stacks vcs-config))]
    (swap! app-state assoc ::stacks stacks)
    (tty/run-ui!
      (tty/render! app-state
        (tty/component
          {:on-key-press #(when (= % (int \q))
                            (tty/close!))}
          (fn [state]
            (tty/block
              [(render-stacks
                 {:stacks stacks
                  ::prs (::prs state)
                  :vcs-config vcs-config
                  :include-prs? true})
               (render-keybindings)])))))))
