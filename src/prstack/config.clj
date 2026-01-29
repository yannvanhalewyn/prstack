(ns prstack.config
  (:require
    [bb-tty.ansi :as ansi]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pp]
    [clojure.string :as str]
    [prstack.utils :as u])
  (:import
    (java.io PushbackReader)))

(def UserConfig
  [:map
   [:vcs :keyword]
   [:ignored-branches :set :string]
   [:feature-base-branches :set :string]])

(def GlobalConfig
  [:map
   [:diffview-cmd [:or
                   :string
                   [:sequential [:string]]
                   [:fn (fn [x]
                          (and (sequential? x)
                               (#{:shell :pipe} (first x))
                               (every? string? (rest x))))]]]])

(defn read-local []
  (let [config (let [file (io/file ".prstack/config.edn")]
                 (when (.exists file)
                   (with-open [rdr (io/reader file)]
                     (edn/read (PushbackReader. rdr)))))]
    (-> (or config {})
      (update :ignored-branches #(set (or % [])))
      (update :feature-base-branches #(set (or % []))))))

(defn write-local [config]
  (let [file (io/file ".prstack/config.edn")
        dir (.getParentFile file)]
    ;; Create .prstack directory if it doesn't exist
    (when-not (.exists dir)
      (.mkdirs dir))
    ;; Convert sets to vectors for EDN serialization
    (let [config-to-write (-> config
                            (update :ignored-branches set)
                            (update :feature-base-branches set))]
      (with-open [wtr (io/writer file)]
        (pp/pprint config-to-write wtr)))))

(defn read-global []
  (let [config-dir (io/file (System/getProperty "user.home") ".config" "prstack")
        config-file (io/file config-dir "config.edn")]
    (when (.exists config-file)
      (with-open [rdr (io/reader config-file)]
        (try
          (edn/read (PushbackReader. rdr))
          (catch Exception e
            (println (ansi/colorize :yellow "Warning:" )
              "Error reading EDN config file at ~/.config/prstack/config.edn:" (ex-message e))
            nil))))))

(defn- substitute-placeholders
  "Replaces placeholders in a command or string with actual values"
  [cmd placeholders]
  (cond
    ;; Vector of command segments
    (vector? cmd)
    (mapv (fn [arg]
            (reduce (fn [s [placeholder value]]
                      (str/replace s placeholder value))
                    arg
                    placeholders))
          cmd)

    ;; String (for :shell commands)
    (string? cmd)
    (reduce (fn [s [placeholder value]]
              (str/replace s placeholder value))
            cmd
            placeholders)

    :else cmd))

(comment
  (substitute-placeholders ["git" "diff" "$from-sha..$to-sha"]
    {"$from-sha" "abc123"
     "$to-sha" "def456"}))

(defn get-diffview-cmd
  "Returns diffview command configuration with strategy.
  Returns a map with :strategy and :value keys.

  Supported formats in config:
  - [:pipe [\"git\" \"diff\" ...] [\"less\" \"-R\"]] - Pipeline of commands
  - [:shell \"git diff ... | less\"] - Shell command string
  - [\"git\" \"diff\" ...] - Single command (legacy, treated as 1-element pipeline)

  Placeholders $from-sha and $to-sha will be replaced with actual values."
  [global-config from-sha to-sha]
  (let [placeholders {":from-sha" from-sha
                      ":to-sha" to-sha
                      "$from-sha" from-sha
                      "$to-sha" to-sha}
        diffview-cmd (u/vectorize (:diffview-cmd global-config))
        default-strategy {:strategy :pipe
                          :value [["git" "--no-pager" "diff" "--color=always" (str from-sha ".." to-sha)]
                                  [(or (System/getenv "PAGER") "less") "-R"]]}]
    (cond
      ;; Keyword-based dispatch
      (keyword? (first diffview-cmd))
      (let [[strategy & args] diffview-cmd]
        (case strategy
          :pipe {:strategy :pipe
                 :value (mapv #(substitute-placeholders % placeholders) args)}
          :shell {:strategy :shell
                  :value (substitute-placeholders (first args) placeholders)}
          default-strategy))
      ;; Standard commands
      (seq diffview-cmd)
      {:strategy :shell
       :value (substitute-placeholders diffview-cmd placeholders)}
      ;; Default
      :else default-strategy)))

(comment
  (read-local)
  (read-global)
  (get-diffview-cmd
    ;;{:diffview-cmd "git"}
    (read-global)
    "abc123" "def456"))
