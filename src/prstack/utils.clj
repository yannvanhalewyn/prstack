(ns prstack.utils
  (:require
    [babashka.process :as p]
    [bb-tty.ansi :as ansi]
    [clojure.string :as str]))

(defn find-first [pred coll]
  (first (filter pred coll)))

(defn indexed
  "Returns an list of tuples of the form [index item]. Returns a transducer when called with no arguments"
  [coll]
  (map-indexed vector coll))

(defn find-index [pred coll]
  (some
    (fn [[idx el]]
      (when (pred el)
        idx))
    (indexed coll)))

(defn dissoc-in
  "Dissoc's the element at path in coll"
  [coll path]
  (if (= 1 (count path))
    (dissoc coll (first path))
    (update-in coll (butlast path) dissoc (last path))))

(defn vectorize [x]
  (cond
    (nil? x) []
    (sequential? x) (vec x)
    :else
    (vector x)))

(defn distinct-by
  "Returns a vector of the elements of coll without duplicates by `(f el)`,
  keeping the first occurrence and the original order."
  [f coll]
  (second
    (reduce
      (fn [[seen ret] el]
        (let [k (f el)]
          (if (contains? seen k)
            [seen ret]
            [(conj seen k) (conj ret el)])))
      [#{} []]
      coll)))

(defn exception-message
  "Returns the trimmed message of exception `e`, or its class name when it has
  no message, like a StackOverflowError."
  [e]
  (or (some-> (ex-message e) str/trim not-empty)
      (str (class e))))

(defn consecutive-pairs [coll]
  (map vector coll (rest coll)))

(defn build-index
  "Reduces a coll into a map as follows:
    {(key-fn el) (val-fn el)}

  When no val-fn is supplied will create a hashmap with the elements keyed to
  their key-fn:
    {(key-fn el) el}

  @example
  (def users
    [{:name \"John\" :id 2} {:name \"Jeff\" :id 3}])

  (build-index :id users)
  ;; => {2 {:name \"John\" :id 2}
         3 {:name \"Jeff\" :id 3}}

  (build-index :id :name users)
  ;; => {2 \"John\"
         3 \"Jeff\"}

  Useful when building an index out of a list. Using this prevents combining
  `key-by` with `map-vals`, or to replace `(into {})`."
  ([key-fn coll]
   (build-index key-fn identity coll))
  ([key-fn val-fn coll]
   (persistent!
     (reduce #(assoc! %1 (key-fn %2) (val-fn %2)) (transient {}) coll))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shell

(defn binary-exists? [binary-name]
  (try
    (let [result (-> (ProcessBuilder. ["which" binary-name])
                   (.start)
                   (.waitFor))]
      (zero? result))
    (catch Exception _
      false)))

(defn run-cmd [cmd & [{:keys [echo? dir]}]]
  (when echo?
    (println (ansi/colorize :gray (str "$ " (str/join " " cmd)))))
  (-> (p/process cmd {:out :string :dir dir})
    p/check
    :out
    str/trim))

(defn shell [cmd & [{:keys [echo? dir]}]]
  (when echo?
    (println (ansi/colorize :gray (str "$ " (str/join " " cmd)))))
  (-> (p/shell cmd {:out :string :dir dir})
    p/check
    :out
    str/trim))

(defn shell-out [cmd & [{:keys [echo? dir]}]]
  (when echo?
    (println (ansi/colorize :gray (str "$ " (str/join " " cmd)))))
  (-> (p/shell cmd {:inherit :true :dir dir})
    p/check))

(defn shell-out-interactive
  "Runs an interactive shell command, properly handling terminal state"
  [cmd & [{:keys [echo? dir]}]]
  (when echo?
    (println (ansi/colorize :gray (str "$ " (str/join " " cmd)))))
  (if (System/getenv "TERM")
    ;; Restore normal terminal mode for interactive commands
    (let [original-state (try (-> (p/process ["stty" "-g"] {:out :string}) p/check :out str/trim)
                           (catch Exception _ nil))]
      (try
        (when original-state
          (p/shell ["stty" original-state] {:inherit true}))
        (-> (p/shell cmd {:inherit :true :dir dir})
          p/check)
        (finally
          (when original-state
            (try
              (p/shell ["stty" "raw" "echo"] {:inherit true})
              (catch Exception _))))))
    ;; Fallback for non-terminal environments
    (-> (p/shell cmd {:inherit :true :dir dir})
      p/check)))

(def ^:private worker-stack-size
  "Stack size in bytes for threads started by `start-large-stack-thread!`.
  Larger than the main thread's stack."
  (* 64 1024 1024))

(defn start-large-stack-thread!
  "Runs `f` on a new thread with a large stack. Babashka's default thread stack
  is much smaller than the main thread's, so deeply recursive code that works
  on the main thread can overflow in a `future`."
  [f]
  (doto (Thread. nil f "prstack-worker" worker-stack-size)
    (.start)))

(defn pmap-large-stack
  "Like `pmap`, but runs `f` on a separate thread with a large stack for every
  item, see `start-large-stack-thread!`, returning a vector of the results.
  Exceptions thrown by `f` are rethrown."
  [f coll]
  (->> coll
    (mapv (fn [x]
            (let [result (promise)]
              (start-large-stack-thread!
                #(deliver result
                   (try [(f x)]
                     (catch Throwable e [nil e]))))
              result)))
    (mapv (fn [result]
            (let [[v e] @result]
              (if e (throw e) v))))))

(defn pipeline
  "Executes a pipeline of commands, piping output from one to the next.

  cmds can be either:
  - A vector of command vectors: [[\"git\" \"diff\" \"a..b\"] [\"less\" \"-R\"]]
  - A single command vector: [\"git\" \"diff\" \"a..b\"]

  Options:
  - :echo? - Print commands being executed
  - :inherit-last - If true, last command inherits stdout (for interactive pagers)
  - :dir - Working directory for all commands"
  [cmds & [{:keys [echo? inherit-last dir] :or {inherit-last true}}]]
  (when echo?
    (println
      (ansi/colorize :gray
        (->> (if (vector? (first cmds)) cmds [cmds])
          (map #(str/join " " %))
          (str/join " | ")
          (str "$ ")))))

  (let [cmds (if (vector? (first cmds)) cmds [cmds])
        processes
        (reduce
          (fn [acc cmd]
            (let [prev-proc (last acc)
                  is-last? (= cmd (last cmds))
                  opts
                  (cond
                    ;; Last command in pipeline - inherit stdin/stdout/stderr
                    (and inherit-last is-last?)
                    {:in (:out prev-proc) :inherit true}

                    ;; Middle command - read from previous, write to pipe
                    prev-proc
                    {:in (:out prev-proc)}

                    ;; First command - just write to pipe
                    :else
                    {})]
              (conj acc (p/process cmd (assoc opts :dir dir)))))
          []
          cmds)]
    ;; Wait for all processes to complete
    (doseq [proc processes]
      (p/check proc))))
