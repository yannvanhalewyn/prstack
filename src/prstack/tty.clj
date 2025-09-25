(ns prstack.tty
  (:require
    [babashka.process :as p]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [prstack.utils :as u])
  (:import
    [java.util.concurrent CancellationException]))

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
   :gray "\033[90m"
   :bg-light-gray "\033[47m"
   :bg-gray "\033[100m"})

(def colorize
  (if (System/getenv "NO_COLORS")
    (fn [_ text]
      text)
    (fn [color-keys text]
      (let [color-codes (str/join "" (map colors (u/vectorize color-keys)))]
        (str color-codes text (colors :reset))))))

;; Terminal state management
(defn run-in-raw-mode [f]
  (let [original-state (-> (p/shell {:out :string} "stty -g")
                         :out str/trim)]
    (try
      (p/shell "stty raw -echo")
      (f)
      (finally
        (p/shell (str "stty " original-state))))))

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
     (render-tree (comp state) state acc)

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
        (let [render (render-tree components new-state)]
          (refresh-screen! (::lines render))
          (register-key-handlers (keep :on-key-press (::handlers render))))))))

(defn register-key-handler [f]
  (future
    (loop []
      (f (.read System/in))
      (recur))))

(defn- with-running-ui
  "Runs function with UI system active"
  [f]
  (let [key-handler (register-key-handler
                      (fn [k]
                        (when-let [f (::keydown-handler @running-ui)]
                          (f k))))]
    (in-raw-mode
      (try
        (enter-fullscreen!)
        (reset! running-ui {::close-fns [#(future-cancel key-handler)]})
        (f)
        @key-handler
        (catch CancellationException _e) ;; The keyhandler can get cancelled
        (finally
          (exit-fullscreen!)
          (doseq [f (::after-close-fns @running-ui)]
            (f)))))))

(defn close!
  ([]
   (close! {}))
  ([{:keys [after-close]}]
   (when after-close
     (swap! running-ui update ::after-close-fns conj after-close))
   (let [close-fns (::close-fns @running-ui)]
     (doseq [f close-fns]
       (f)))))

(defmacro run-ui! [& body]
  `(with-running-ui (fn [] ~@body)))
