(ns bb-tty.tui
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [clojure.string :as str])
  (:import
    (java.util.concurrent ExecutionException)))

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
         ;; Using `into`, `concat` returns a seq where `conj` would prepend
         (-> acc
           (update ::lines into (::lines result))
           (update ::handlers into (::handlers result)))
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
                 ;; No trailing padding, writing up to the last column can wrap
                 ;; the line on terminals that render some glyphs wider
                 (for [line lines]
                   (str (str/join (repeat left " "))
                        (ansi/truncate line max-content-width)))
                 (repeat bottom "")))
             lines)))))))

(def ^:private scroll-offsets
  "The scroll offset of every scroll view by id. Rendering shouldn't change the
  state, as that triggers another render, so offsets are kept here."
  (atom {}))

(defn- clamp [x lo hi]
  (-> x (max lo) (min hi)))

(defn- scroll-offset
  "Returns the offset of a view of `height` lines that keeps the line at index
  `focus` at least `margin` lines away from its edges, scrolling as little as
  possible from `offset`."
  [offset {:keys [height focus margin line-count]}]
  (let [margin (min margin (quot (dec height) 2))
        offset (if focus
                 (clamp offset (+ (- focus height) 1 margin) (- focus margin))
                 offset)]
    (clamp offset 0 (- line-count height))))

(defn- scroll-indicator [arrow n]
  (if (pos? n)
    (ansi/colorize :gray (str arrow " " n " more"))
    ""))

(defn scroll-view
  "Renders the lines of `children` in a view of `height` lines, scrolled to keep
  the line at index `focus` in view. Indicators show how many lines are hidden
  above and below. Pads the lines to `height`, so that what comes after stays
  in place. Every view needs a unique `id` to remember its scroll offset."
  [{:keys [id height focus margin] :or {margin 2}} children]
  (fn [state]
    (let [result (render-tree children state)
          lines (vec (::lines result))
          line-count (count lines)]
      (cond
        (nil? height)
        result

        (<= line-count height)
        (assoc result ::lines (into lines (repeat (- height line-count) "")))

        :else
        ;; Always reserves a line above and below for the indicators, so the
        ;; lines don't shift when one appears
        (let [indicators? (>= height 3)
              view-height (if indicators? (- height 2) (max height 0))
              offset (scroll-offset (get @scroll-offsets id 0)
                       {:height view-height
                        :focus focus
                        :margin margin
                        :line-count line-count})
              visible (subvec lines offset (+ offset view-height))]
          (swap! scroll-offsets assoc id offset)
          (assoc result ::lines
            (if indicators?
              (concat [(scroll-indicator "↑" offset)]
                visible
                [(scroll-indicator "↓" (- line-count offset view-height))])
              visible)))))))

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
  ;; No newline after the last line, on the last row it would scroll the
  ;; screen up
  (print (str/join "\r\n" lines))
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

(def ^:private render-lock (Object.))

(defn mount!
  "Renders current state to screen and adds a watch to the state atom for
  re-rendering. The state may be updated from multiple threads, so renders
  are serialized and always render the latest state."
  [state components]
  (let [render! (fn []
                  (locking render-lock
                    (let [render (render-tree components @state)]
                      (refresh-screen! (::lines render))
                      (register-key-handlers (keep :on-key-press (::handlers render))))))]
    (render!)
    (add-watch state ::renderer
      (fn [_ _ _ _new-state]
        (render!)))
    (swap! running-ui update ::close-fns conj
      #(remove-watch state ::renderer)
      (tty/on-resize render!))))

(defn run-event-loop!
  "Calls `f` with every key pressed, see `tty/read-key`."
  [f]
  (let [running? (atom true)
        event-loop
        (future
          (loop []
            (when @running?
              ;; Waits for keys in short intervals, to stop soon after
              ;; `close!`
              (let [key (tty/read-key 100)]
                (when-not (= ::tty/timeout key)
                  (f key))
                (recur)))))]
    [event-loop #(reset! running? false)]))

(declare close!)

(defn with-running-ui
  "Runs function with UI system active"
  [f]
  ;; Only start reading keys in raw mode, setting up the terminal can change
  ;; its attributes, which could end a read that has already started
  (tty/in-raw-mode
    (let [[event-loop stop-event-loop]
          (run-event-loop!
            (fn [k]
              (when-let [f (::keydown-handler @running-ui)]
                (f k))))]
      (try
        (enter-fullscreen!)
        (reset! running-ui
          {::close-fns [stop-event-loop]})
        (f)
        @event-loop
        (catch ExecutionException e
          ;; Rethrow errors from the event loop as is
          (throw (or (ex-cause e) e)))
        (finally
          ;; Stops rendering when exiting because of an error
          (close!)
          ;; TODO we shouldn't exit fullscreen when running a command in
          ;; foreground
          (exit-fullscreen!))))))

(defmacro run-ui! [& body]
  `(with-running-ui (fn [] ~@body)))

(defn close! []
  (doseq [f (::close-fns @running-ui)]
    (f))
  (swap! running-ui dissoc ::close-fns))
