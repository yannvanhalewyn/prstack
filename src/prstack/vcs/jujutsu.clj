(ns prstack.vcs.jujutsu
  "Jujutsu implementation of the VCS protocol.

  This implementation uses Jujutsu (jj) commands to manage PR stacks,
  leveraging jj's change-based model and powerful revset queries."
  (:require
    [clojure.string :as str]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn installed? []
  (u/binary-exists? "jj"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration

(defn detect-trunk-branch
  "Detects wether the trunk branch is named 'master' or 'main'"
  [vcs]
  (first
    (into []
      (comp
        (map #(str/replace % #"\*" ""))
        (filter #{"master" "main"}))
      (str/split-lines
        (u/run-cmd
          ["jj" "bookmark" "list"
           "-T" "self ++ \"\n\""]
          {:dir (:vcs/project-dir vcs)})))))

(defn detect-trunk-branch! [vcs]
  (or (detect-trunk-branch vcs)
      (throw (ex-info "Could not detect trunk branch" {}))))

(defn config
  "Reads the VCS configuration"
  [vcs]
  {:vcs-config/trunk-branch (detect-trunk-branch! vcs)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Branch Operations

(defn push-branch! [vcs branch-name]
  ;; First try to track the remote bookmark if it exists (ignoring errors if it doesn't)
  ;; This is because --allow-new got deprecated, and some situations may arise
  ;; where the remote bookmark is not tracked.
  (try
    (u/run-cmd ["jj" "bookmark" "track" branch-name "--remote" "origin"]
      {:echo? true :dir (:vcs/project-dir vcs)})
    (catch Exception e
      (println "Error tracking bookmark" branch-name (ex-message e))))
  ;; Then push
  (u/run-cmd ["jj" "git" "push" "-b" branch-name]
    {:echo? true :dir (:vcs/project-dir vcs)}))


(defn fetch! [vcs]
  (u/run-cmd ["jj" "git" "fetch"]
    {:echo? true :dir (:vcs/project-dir vcs)}))

(defn set-bookmark-to-remote! [vcs branch-name]
  (u/run-cmd ["jj" "bookmark" "set" branch-name
              "-r" (str branch-name "@origin")
              "--allow-backwards"]
    {:echo? true :dir (:vcs/project-dir vcs)}))

(defn rebase-on-trunk! [vcs trunk-branch]
  (u/run-cmd ["jj" "rebase" "-d" trunk-branch]
    {:echo? true :dir (:vcs/project-dir vcs)}))

(defn push-tracked! [vcs]
  (u/run-cmd ["jj" "git" "push" "--tracked"]
    {:echo? true :dir (:vcs/project-dir vcs)}))

(defn delete-bookmark! [vcs bookmark-name]
  (u/run-cmd ["jj" "bookmark" "delete" bookmark-name]
    {:dir (:vcs/project-dir vcs) :echo? true}))

(defn list-local-bookmarks [vcs]
  (let [output (u/run-cmd ["jj" "bookmark" "list" "-T" "name ++ \"\\n\""]
                 {:dir (:vcs/project-dir vcs)})]
    (into []
      (comp
        (map str/trim)
        (remove empty?))
      (str/split-lines output))))

(defn get-change-id [vcs ref]
  (str/trim
    (u/run-cmd ["jj" "log" "--no-graph" "-r" ref "-T" "change_id"]
      {:dir (:vcs/project-dir vcs)})))

(defn rebase-on! [vcs target-ref]
  (u/run-cmd ["jj" "rebase" "-d" target-ref]
    {:dir (:vcs/project-dir vcs) :echo? true}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Change data structure

(defn remote-branchname [change]
  (u/find-first
    #(not (str/ends-with? % "@git"))
    (:change/remote-branchnames change)))

(defn find-fork-point
  "Finds the fork point (common ancestor) between a ref and trunk.

  Uses jj's fork_point() revset function to find where the ref diverged
  from the trunk line."
  [vcs ref]
  (str/trim
    (u/run-cmd
      ["jj" "log" "--no-graph"
       "-r" (format "fork_point(trunk() | %s)" ref)
       "-T" "change_id"]
      {:dir (:vcs/project-dir vcs)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph operations

(defn- parse-branchnames [s]
  (if (empty? s)
    []
    ;; Consider keeping 'git' and 'origin' as the remote name next to the
    ;; remote branch.
    (->> (str/split s #" ")
      ;; Remove names with @git suffix
      (remove #(str/ends-with? % "@git"))
      ;; Truncate @... suffixes
      (map #(str/replace % #"\@.*" ""))
      ;; Truncate trailing asterisk
      (map #(str/replace % #"\*$" "")))))

(def ^:private separator
  "#PRSTACK#")

(comment
  (str/split "foo#PRSTACK#bar#PRSTACK#baz" #"#PRSTACK#"))

(defn- parse-log
  "Parses jj log output into a collection of node maps."
  [output]
  (when (not-empty output)
    (into []
      (comp
        (map str/trim)
        (remove empty?)
        (map #(str/split % (re-pattern separator)))
        (map (fn [[change-id commit-sha parents-str local-branches-str remote-branches-str]]
               {:change/change-id change-id
                :change/commit-sha commit-sha
                :change/parent-ids (if (empty? parents-str)
                                     []
                                     (str/split parents-str #" "))
                :change/local-branchnames (parse-branchnames local-branches-str)
                :change/remote-branchnames (parse-branchnames remote-branches-str)})))
      (str/split-lines output))))

(def ^:private log-template
  (str "separate('#PRSTACK#', "
       "change_id, "
       "commit_id, "
       "parents.map(|p| p.change_id()).join(' '), "
       "coalesce(local_bookmarks.join(' '), ' '), "
       "remote_bookmarks.join(' ')) "
       "++ \"\\n\""))

(defn- read-nodes
  [vcs revset]
  {:nodes (parse-log
            (u/run-cmd
              ["jj" "log" "--no-graph"
               "-r" revset
               "-T" log-template]
              {:dir (:vcs/project-dir vcs)}))
   :trunk-change-id
   (str/trim
     (u/run-cmd
       ["jj" "log" "--no-graph"
        "-r" (:vcs-config/trunk-branch (vcs/vcs-config vcs))
        "-T" "change_id"]
       {:dir (:vcs/project-dir vcs)})) })

(defn read-all-nodes
  "Reads the full VCS graph from jujutsu.

  Reads all changes from trunk to all bookmark heads.

  Returns `:nodes` and a `:trunk-change-id` for the VCS graph."
  [vcs]
  ;; Get all changes from any trunk commit to all bookmark heads
  ;; This uses trunk()::bookmarks() to include stacks that forked from
  ;; old trunk commits (before trunk advanced), rather than restricting
  ;; to descendants of the current trunk bookmark position
  (read-nodes vcs "trunk()::bookmarks()"))

(defn read-current-stack-nodes
  "Reads a graph specifically for the current working copy stack.

  This includes all changes from trunk's fork-point to @, even if @ is not bookmarked.

  Returns `:nodes` and a `:trunk-change-id` for the VCS graph."
  [vcs]
  ;; Gets all changes from fork point to current
  (read-nodes vcs "fork_point(trunk() | @)::@"))

(defn current-change-id
  "Returns the change-id of the current working copy (@)."
  [vcs]
  (str/trim
    (u/run-cmd ["jj" "log" "--no-graph" "-r" "@" "-T" "change_id"]
      {:dir (:vcs/project-dir vcs)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VCS implementation

(defn- fork-info
  ([vcs]
   (fork-info vcs "@"))
  ([vcs ref]
   (let [trunk-branch (vcs/trunk-branch vcs)
         opts {:dir (:vcs/project-dir vcs)}]
     {:forkpoint-info/fork-point-change-id (find-fork-point vcs ref)
      :forkpoint-info/local-trunk-commit-sha
      (u/run-cmd ["jj" "log" "--no-graph"
                  "-r" "fork_point(trunk() | @)"
                  "-T" "change_id"]
        opts)
      :forkpoint-info/remote-trunk-commit-sha
      (u/run-cmd ["jj" "log" "--no-graph"
                  "-r" (str trunk-branch "@origin")
                  "-T" "change_id"]
        opts)})))

(defrecord JujutsuVCS []
  vcs/VCS
  (read-vcs-config [this]
    (config this))

  (push-branch! [this branch-name]
    (push-branch! this branch-name))

  (remote-branchname [_this change]
    (remote-branchname change))

  (read-all-nodes [this]
    (read-all-nodes this))

  (read-current-stack-nodes [this]
    (read-current-stack-nodes this))

  (current-change-id [this]
    (current-change-id this))

  (find-fork-point [this ref]
    (find-fork-point this ref))

  (fork-info [this]
    (fork-info this))

  (fetch! [this]
    (fetch! this))

  (set-bookmark-to-remote! [this branch-name]
    (set-bookmark-to-remote! this branch-name))

  (rebase-on-trunk! [this]
    (rebase-on-trunk! this (vcs/trunk-branch this)))

  (push-tracked! [this]
    (push-tracked! this))

  (delete-bookmark! [this bookmark-name]
    (delete-bookmark! this bookmark-name))

  (list-local-bookmarks [this]
    (list-local-bookmarks this))

  (get-change-id [this ref]
    (get-change-id this ref))

  (rebase-on! [this target-ref]
    (rebase-on! this target-ref)))
