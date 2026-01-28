(ns prstack.init
  (:require
    [bb-tty.ansi :as ansi]
    [bb-tty.tty :as tty]
    [clojure.java.io :as io]
    [prstack.config :as config]
    [prstack.utils :as u]))

(defn- jj-installed? []
  (u/binary-exists? "jj"))

(defn- read-user-choice [prompt options]
  (let [selected (first
                   (tty/prompt-pick
                     {:prompt prompt
                      :options options
                      :render-option :label}))]
    (when selected
      ;; Find the option that matches the selected label
      (first (filter #(= (:label %) selected) options)))))

(defn- config-file-exists? []
  (.exists (io/file ".prstack/config.edn")))

(defn- welcome-message []
  (println)
  (println (ansi/colorize :cyan "╔══════════════════════════════════════════════╗"))
  (println (ansi/colorize :cyan "║      Welcome to PRStack!                     ║"))
  (println (ansi/colorize :cyan "╚══════════════════════════════════════════════╝"))
  (println)
  (println "PRStack helps you manage stacked PRs with ease.")
  (println))

(defn- initial-setup! []
  (welcome-message)
  (println "Let's get you set up. This will create a " (ansi/colorize :bold ".prstack/config.edn") ".")
  (println "First, choose your version control system:")
  (println)

  (let [choice (read-user-choice
                 "Which VCS would you like to use?"
                 [{:label "Jujutsu (recommended)" :value :jujutsu}
                  {:label "Git" :value :git}])]
    (if choice
      (:value choice)
      (do
        (println (ansi/colorize :red "\nError: Invalid choice. Exiting."))
        (System/exit 1)))))

(defn- prompt-jj-not-found []
  (println)
  (println (ansi/colorize :yellow "Warning: Jujutsu (jj) binary not found!"))
  (println)
  (println "Jujutsu is not installed on your system.")
  (println "You can install it from: https://martinvonz.github.io/jj/latest/install-and-setup/")
  (println)

  (let [choice (read-user-choice
                 "What would you like to do?"
                 [{:label "Exit and install Jujutsu (recommended)" :value :exit}
                  {:label "Use Git instead" :value :git}])]
    (if choice
      (:value choice)
      (do
        (println (ansi/colorize :red "\nError: Invalid choice. Exiting."))
        (System/exit 1)))))

(defn ensure-initialized! []
  ;; Check if config file exists
  (let [config-exists? (config-file-exists?)
        config (when config-exists? (config/read-local))
        vcs (or (:vcs config)
                (initial-setup!))]

    ;; Check if selected VCS binary exists
    (let [final-vcs
          (cond
            ;; Jujutsu selected but not installed
            (and (= vcs :jujutsu) (not (jj-installed?)))
            (let [choice (prompt-jj-not-found)]
              (if (= choice :exit)
                (do
                  (println)
                  (println "Please install Jujutsu and run prstack again.")
                  (System/exit 0))
                choice))
            ;; Everything is fine
            :else
            vcs)]

      ;; Write config if it doesn't exist or VCS changed
      ;; This can happen either when config file doesn't exist, or when it
      ;; didn't have a value for the `:vcs` key
      (when (not= final-vcs (:vcs config))
        (config/write-local
          (merge
            {:vcs final-vcs
             :ignored-branches #{}
             :feature-base-branches #{}}
            config)))

      ;; Return the final VCS choice
      final-vcs)))
