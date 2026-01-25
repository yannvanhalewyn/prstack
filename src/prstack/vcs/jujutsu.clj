(ns prstack.vcs.jujutsu
  "Jujutsu implementation of the VCS protocol.

  This implementation uses Jujutsu (jj) commands to manage PR stacks,
  leveraging jj's change-based model and powerful revset queries."
  (:require
    [clojure.string :as str]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration

(defn detect-trunk-branch
  "Detects wether the trunk branch is named 'master' or 'main'"
  []
  (first
    (into []
      (comp
        (map #(str/replace % #"\*" ""))
        (filter #{"master" "main"}))
      (str/split-lines
        (u/run-cmd
          ["jj" "bookmark" "list"
           "-T" "self ++ \"\n\""])))))

(defn detect-trunk-branch! []
  (or (detect-trunk-branch)
      (throw (ex-info "Could not detect trunk branch" {}))))

(defn config
  "Reads the VCS configuration"
  []
  {:vcs-config/trunk-branch (detect-trunk-branch!)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Branch Operations

(defn push-branch [branch-name]
  (u/run-cmd ["jj" "git" "push" "-b" branch-name "--allow-new"]
    {:echo? true}))

(defn fetch! []
  (u/run-cmd ["jj" "git" "fetch"]
    {:echo? true}))

(defn set-bookmark-to-remote! [branch-name]
  (u/run-cmd ["jj" "bookmark" "set" branch-name
              "-r" (str branch-name "@origin")]
    {:echo? true}))

(defn rebase-on-trunk! [trunk-branch]
  (u/run-cmd ["jj" "rebase" "-d" trunk-branch]
    {:echo? true}))

(defn push-tracked! []
  (u/run-cmd ["jj" "git" "push" "--tracked"]
    {:echo? true}))

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
  [ref]
  (str/trim
    (u/run-cmd
      ["jj" "log" "--no-graph"
       "-r" (format "fork_point(trunk() | %s)" ref)
       "-T" "change_id.short()"])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph operations

(defn- remove-asterisk-from-branch-name [branch-name]
  (when branch-name
    (str/replace branch-name #"\*$" "")))

(defn- parse-branchnames [log-str]
  (if (empty? log-str)
    []
    (map remove-asterisk-from-branch-name
      (str/split log-str #" "))))

(defn- parse-log
  "Parses jj log output into a collection of node maps."
  [output]
  (when (not-empty output)
    (into []
      (comp
        (map str/trim)
        (remove empty?)
        (map #(str/split % #";"))
        (map (fn [[change-id commit-sha parents-str local-branches-str remote-branches-str]]
               {:change/change-id change-id
                :change/commit-sha commit-sha
                :change/parent-ids (if (empty? parents-str)
                                     []
                                     (str/split parents-str #" "))
                :change/local-branchnames (parse-branchnames local-branches-str)
                :change/remote-branchnames (parse-branchnames remote-branches-str)})))
      (str/split-lines output))))

(defn read-all-nodes
  "Reads the full VCS graph from jujutsu.

  Reads all commits from trunk to all bookmark heads, building a complete
  graph representation with parent/child relationships.

  Returns a Graph (see prstack.vcs.graph/Graph)"
  [{:vcs-config/keys [trunk-branch]}]
  (let [trunk-change-id
        (str/trim
          (u/run-cmd
            ["jj" "log" "--no-graph" "-r" trunk-branch
             "-T" "change_id.short()"]))
        ;; Get all changes from any trunk commit to all bookmark heads
        ;; This uses trunk()::bookmarks() to include stacks that forked from
        ;; old trunk commits (before trunk advanced), rather than restricting
        ;; to descendants of the current trunk bookmark position
        revset "trunk()::bookmarks()"
        output (u/run-cmd
                 ["jj" "log" "--no-graph"
                  "-r" revset
                  "-T" (str "separate(';', "
                            "change_id.short(), "
                            "commit_id, "
                            "parents.map(|p| p.change_id().short()).join(' '), "
                            "local_bookmarks.join(' '), "
                            "remote_bookmarks.join(' ')) "
                            "++ \"\\n\"")])]
    {:nodes (parse-log output)
     :trunk-change-id trunk-change-id}))

(defn current-change-id
  "Returns the change-id of the current working copy (@)."
  []
  (str/trim (u/run-cmd ["jj" "log" "--no-graph" "-r" "@" "-T" "change_id.short()"])))

(defn read-current-stack-nodes
  "TODO fix Reads a graph specifically for the current working copy stack.

  This includes all changes from trunk to @, even if @ is not bookmarked.

  Returns a Graph (see prstack.vcs.graph/Graph)"
  [{:vcs-config/keys [trunk-branch]}]
  {:nodes
   (parse-log
     (u/run-cmd
       ["jj" "log" "--no-graph"
        ;; Gets all changes from fork point to current
        "-r" "fork_point(trunk() | @)::@"
        "-T" (str "separate(';', "
                  "change_id.short(), "
                  "commit_id, "
                  "parents.map(|p| p.change_id().short()).join(' '), "
                  "local_bookmarks.join(' '), "
                  "remote_bookmarks.join(' ')) "
                  "++ \"\\n\"")]))
   :trunk-change-id
   (str/trim
     (u/run-cmd
       ["jj" "log" "--no-graph" "-r" trunk-branch
        "-T" "change_id.short()"]))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VCS implementation

(defn- fork-info
  ([vcs]
   (fork-info vcs "@"))
  ([vcs ref]
   (let [trunk-branch (vcs/trunk-branch vcs)]
     {:fork-info/fork-point-change-id (find-fork-point ref)
      :fork-info/local-trunk-change-id
      (u/run-cmd ["jj" "log" "--no-graph"
                  "-r" "fork_point(trunk() | @)"
                  "-T" "commit_id"])
      :fork-info/remote-trunk-change-id
      (u/run-cmd ["jj" "log" "--no-graph"
                  "-r" (str trunk-branch "@origin")
                  "-T" "commit_id"])})))

(defrecord JujutsuVCS []
  vcs/VCS
  (read-vcs-config [_this]
    (config))

  (push-branch [_this branch-name]
    (push-branch branch-name))

  (remote-branchname [_this change]
    (remote-branchname change))

  (read-all-nodes [this]
    (read-all-nodes (vcs/vcs-config this)))

  (read-current-stack-nodes [this]
    (read-current-stack-nodes (vcs/vcs-config this)))

  (current-change-id [_this]
    (current-change-id))

  (find-fork-point [_this ref]
    (find-fork-point ref))

  (fork-info [this]
    (fork-info this))

  (fetch! [_this]
    (fetch!))

  (set-bookmark-to-remote! [_this branch-name]
    (set-bookmark-to-remote! branch-name))

  (rebase-on-trunk! [this]
    (rebase-on-trunk! (vcs/trunk-branch this)))

  (push-tracked! [_this]
    (push-tracked!)))
