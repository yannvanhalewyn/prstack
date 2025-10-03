(ns bb-tty.tui
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [clojure.string :as str]
    [clojure.tools.logging :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Components

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

(defn block
  "Creates a block component with padding around its children"
  ([children]
   (block {} children))
  ([{:keys [top bottom left right] :or {top 1 bottom 1 left 2 right 2}} children]
   (fn [state]
     (-> (render-tree children state)
       (update ::lines
         (fn [lines]
           (if-let [{:keys [cols]} (tty/get-terminal-size)]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Screen Management

(defn enter-fullscreen! []
  (print ansi/ALT_SCREEN) ;; Go into A
  (print ansi/HIDE_CURSOR)
  (print ansi/CLEAR_SCREEN)
  (print ansi/CURSOR_HOME)
  (flush))

(defn exit-fullscreen! []
  (print ansi/SHOW_CURSOR)
  (print ansi/NORMAL_SCREEN)
  (flush))

(defn clear-screen! []
  (print ansi/CLEAR_SCREEN)
  (print ansi/CURSOR_HOME)
  (flush))

(defn refresh-screen!
  "Renders lines to terminal"
  [lines]
  (clear-screen!)
  (doseq [line lines]
    (print line "\r\n"))
  (flush))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mounting UI

(def ^:private running-ui (atom nil))

(defn- register-key-handlers [handlers]
  (let [handlers (reverse handlers)]
    (swap! running-ui assoc ::keydown-handler
      (fn [k]
        (loop [[cur & others] handlers]
          (when (and (not (cur k)) (seq others))
            (recur others)))))))

(defn mount!
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
          (register-key-handlers (keep :on-key-press (::handlers render))))))
    (swap! running-ui update ::close-fns conj
      #(remove-watch state ::renderer))))

(defn run-event-loop! [f]
  (let [running? (atom true)
        event-loop
        (future
          (loop []
            (when @running?
              (let [key (.read System/in)]
                ;; Consume the key without echoing it to terminal
                ;; As long as we call `close!` synchronously in this handler
                ;; it will shut down gracefully. If not one more character will
                ;; be read before shutdown.
                (f key)
                (recur))))
          (spit "target/dev.log" "Stopping event loop\n" :append true))]
    [event-loop #(reset! running? false)]))

(defn with-running-ui
  "Runs function with UI system active"
  [f]
  (let [[event-loop stop-event-loop]
        (run-event-loop!
          (fn [k]
            (when-let [f (::keydown-handler @running-ui)]
              (f k))))]
    (tty/in-raw-mode
      (try
        (enter-fullscreen!)
        (reset! running-ui
          {::close-fns [stop-event-loop]})
        (f)
        @event-loop
        (catch Exception e
          (spit "target/dev.log"
            (str "Error running UI: " e "\n"
                 (with-out-str (.printStackTrace e)))
            :append true)
          (log/error "Error running UI" e))
        (finally
          ;; TODO we shouldn't exit fullscreen when running a command in
          ;; foreground
          (exit-fullscreen!))))))

(defmacro run-ui! [& body]
  `(with-running-ui (fn [] ~@body)))

(defn close! []
  (doseq [f (::close-fns @running-ui)]
    (f))
  (swap! running-ui dissoc ::close-fns))
