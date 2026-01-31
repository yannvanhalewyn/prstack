(ns prstack.config
  (:require
    [bb-tty.ansi :as ansi]
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pp]
    [prstack.custom-command :as custom-command])
  (:import
    (java.io PushbackReader)))

(def UserConfig
  [:map
   [:vcs :keyword]
   [:ignored-branches :set :string]
   [:feature-base-branches :set :string]])

;; TODO likely we'd want to merge a global config with a local config
;; This will allow users to decide themselves if they want to configure
;; something globally or project only
(def GlobalConfig
  [:map
   [:diffview-cmd {:optional true} custom-command/Command]
   ;; Keys are mapped to custom commands
   ;; Keys are single-character strings like "d", "D", "x" etc.
   [:commands {:optional true}
    [:map-of :string custom-command/Command]]])

(defn- read-edn [file]
  (when (.exists file)
    (with-open [rdr (io/reader file)]
      (try
        (edn/read {:readers {'env System/getenv}}
          (PushbackReader. rdr))
        (catch Exception e
          (println (ansi/colorize :yellow "Warning:" )
            (str "Error reading EDN config file at " file) (ex-message e))
          nil)))))

(defn read-local []
  (read-edn (io/file ".prstack/config.edn")))

(defn read-global []
  (read-edn
    (io/file (System/getProperty "user.home")
      ".config" "prstack" "config.edn")))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Accessors

(defn diffview-command
  "Returns a prepared diffview command strategy according to context."
  [global-config context]
  (custom-command/prepare-command
    (or (:diffview-cmd global-config)
        ["git" "diff" "$from-sha..$to-sha"])
    context))

(defn custom-command
  "Gets a custom command by keybinding from config. The returned command is
  prepared using the context and can be ran."
  [global-config keybinding context]
  (when-let [cmd-spec (get-in global-config [:keybindings keybinding])]
    (custom-command/prepare-command cmd-spec context)))

(defn has-keybinding?
  [global-config keybinding]
  (contains? (:keybindings global-config) keybinding))

(comment
  (read-local)
  (diffview-command (read-global)
    {:from-sha "abc123" :to-sha "def456"})
  (custom-command (read-global) "x" {}))
