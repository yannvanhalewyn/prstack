(ns prstack.tty
  (:require
    [clojure.core.async :as a]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
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

(def RETURN_KEY 13)
(def UP_KEY 65)
(def DOWN_KEY 66)
(def LEFT_KEY 68)
(def RIGHT_KEY 67)

;; Styling utilities
(defn bolden [s]
  (str "\u001b[1m" s "\u001b[0m"))

(defn green [s]
  (str "\u001b[32m" s "\u001b[0m"))

(defn red [s]
  (str "\u001b[31m" s "\u001b[0m"))

(defn blue [s]
  (str "\u001b[34m" s "\u001b[0m"))

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

;; Terminal dimensions
(defn- has-terminal? []
  (try
    (u/shell ["stty" "-g"])
    true
    (catch Exception _
      false)))

(defn get-terminal-size []
  (when (has-terminal?)
    (try
      (let [result (u/shell ["stty" "size"])]
        (when-not (str/blank? result)
          (when-let [[rows cols] (str/split result #" ")]
            {:rows (Integer/parseInt rows)
             :cols (Integer/parseInt cols)})))
      (catch Exception _
        nil))))

;; Terminal state management
(defn run-in-raw-mode [f]
  (if (has-terminal?)
    (let [original-state (u/shell ["stty" "-g"])]
      (try
        (u/shell ["stty" "raw" "echo"])
        (f)
        (finally
          (u/shell ["stty" original-state]))))
    (do
      (println "Error: TTY mode requires a terminal. Please run this command in a terminal.")
      (System/exit 1))))

(defmacro in-raw-mode [& body]
  `(run-in-raw-mode (fn [] ~@body)))

(defn enter-fullscreen! []
  (print ALT_SCREEN)
  (print HIDE_CURSOR)
  (print CLEAR_SCREEN)
  (print CURSOR_HOME)
  (flush))

(defn exit-fullscreen! []
  (print SHOW_CURSOR)
  (print NORMAL_SCREEN)
  (flush))

(defn clear-screen! []
  (print CLEAR_SCREEN)
  (print CURSOR_HOME)
  (flush))

(defn component
  "Creates a component from a render function that returns lines"
  ([render-fn]
   (component {} render-fn))
  ([opts render-fn]
   {::opts opts
    ::render render-fn}))

(defn render-tree
  "Recursively renders all components, collecting lines and event handlers"
  ([comp state]
   (render-tree comp state {::lines [] ::handlers []}))
  ([comp state acc]
   (cond
     (string? comp)
     (update acc ::lines conj comp)

     (fn? comp)
     (let [result (comp state)]
       (if (and (map? result) (contains? result ::lines))
          ;; Function returned a render result map with lines and handlers
         (-> acc
           (update ::lines concat (::lines result))
           (update ::handlers concat (::handlers result)))
          ;; Function returned normal content, process it normally
         (render-tree result state acc)))

     (sequential? comp)
     (reduce #(render-tree %2 state %1) acc comp)

     (and (map? comp) (contains? comp ::render))
     (render-tree ((::render comp) state)
       state
       (if-let [opts (::opts comp)]
         (update acc ::handlers conj opts)
         acc))

     :else acc)))

(comment
  (render-tree
    (fn [_state] ["Line 1"
                  "Line 2"
                  (fn [_state]
                    ["FN Component"])
                  (component
                    {:on-key-down (fn [key] (println "Key:" key))}
                    (fn [_state]
                      ["UI Component"
                       "Line 2"]))
                  ["foo"]])
    {}))

(defn block
  "Creates a block component with padding around its children"
  ([children]
   (block {} children))
  ([{:keys [top bottom left right] :or {top 1 bottom 1 left 2 right 2}} children]
   (fn [state]
     (-> (render-tree children state)
       (update ::lines
         (fn [lines]
           (if-let [{:keys [rows cols]} (get-terminal-size)]
             (let [max-content-width (- cols left right)]
               (concat
                 (repeat top "")
                 (for [line lines]
                   (str (str/join (repeat left " "))
                        (if (> (count line) max-content-width)
                          (subs line 0 max-content-width)
                          line)
                        (str/join (repeat right " "))))
                 (repeat bottom "")))
             lines)))))))

(defn refresh-screen!
  "Renders lines to terminal"
  [lines]
  (clear-screen!)
  (doseq [line lines]
    (print line "\r\n"))
  (flush))

(def ^:private running-ui (atom nil))

(defn- register-key-handlers [handlers]
  (let [handlers (reverse handlers)]
    (swap! running-ui assoc ::keydown-handler
      (fn [k]
        (loop [[cur & others] handlers]
          (when (and (not (cur k)) (seq others))
            (recur others)))))))

(defn render!
  "Renders current state to screen and adds a watch to the state atom for re-rendering"
  [state components]
  (let [state* @state
        render (render-tree components state*)]
    (refresh-screen! (::lines render))
    (register-key-handlers (keep :on-key-press (::handlers render)))
    (add-watch state ::renderer
      (fn [_ _ _ new-state]
        ;; A bit dirty for now, cleaner would be to remove the watcher when
        ;; unmounting. Likely I need some kind of mount method that unbinds the
        ;; refresh watcher
        (when-not (::closed? @running-ui)
          (let [render (render-tree components new-state)]
            (refresh-screen! (::lines render))
            (register-key-handlers (keep :on-key-press (::handlers render)))))))))

(defn run-event-loop! [f]
  (let [running? (atom true)
        event-loop-chan
        (a/thread
          (loop []
            (when @running?
              (let [key (.read System/in)]
                (spit "target/dev.log" (str "Key handler " key "\n") :append true)
                ;; As long as we call `tty/close!` synchronously in this handler
                ;; it will shut down gracefully
                (f key)
                (recur))))
          (spit "target/dev.log" (str "Stopping event loop\n") :append true))]
    [event-loop-chan #(reset! running? false)]))

(defn with-running-ui
  "Runs function with UI system active"
  [f]
  (let [[event-loop-chan stop-event-loop]
        (run-event-loop!
          (fn [k]
            (when-let [f (::keydown-handler @running-ui)]
              (f k))))]
    (in-raw-mode
      (try
        (enter-fullscreen!)
        (reset! running-ui
          {::close-fns [stop-event-loop]})
        (f)
        (a/<!! event-loop-chan)
        (catch Exception e
          (spit "target/dev.log"
            (str "Error running UI: " e "\n"
                 (with-out-str (.printStackTrace e)))
            :append true)
          (log/error "Error running UI" e))
        (finally
          (exit-fullscreen!))))))

(defn close! []
  (doseq [f (::close-fns @running-ui)]
    (f))
  (swap! running-ui assoc ::closed? true))

(defmacro run-ui! [& body]
  `(with-running-ui (fn [] ~@body)))
