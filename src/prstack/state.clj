(ns prstack.state
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pp]))

(def ^:private state-dir ".prstack")
(def ^:private state-file (str state-dir "/state.edn"))

(defn- ensure-state-dir
  "Ensures the .prstack directory exists"
  []
  (when-not (.exists (io/file state-dir))
    (.mkdirs (io/file state-dir))))

(defn- default-state
  []
  {:prs []})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Manipulation

(defn find-pr [state head-branch base-branch]
  (first
    (filter #(and (= (:head %) head-branch)
                  (= (:base %) base-branch))
      (:prs state))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Persistence

(defn read-state
  "Reads the state from the .edn file, returns default state if file doesn't exist"
  []
  (ensure-state-dir)
  (if (.exists (io/file state-file))
    (try
      (edn/read-string (slurp state-file))
      (catch Exception _
        (default-state)))
    (default-state)))

(defn write-state
  "Writes the state to the .edn file"
  [state]
  (ensure-state-dir)
  (with-open [w (io/writer state-file)]
    (binding [*print-namespace-maps* false]
      (pp/pprint state w))))

(defn update-state!
  "Reads current state, applies update-fn to it, and writes it back"
  [update-fn & args]
  (let [current-state (read-state)
        new-state (apply update-fn current-state args)]
    (write-state new-state)
    new-state))

(defn add-pr! [head-branch base-branch prid]
  (update-state! update :prs conj {:head head-branch :base base-branch :id prid}))
