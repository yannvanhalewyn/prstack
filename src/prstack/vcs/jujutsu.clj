(ns prstack.vcs.jujutsu
  "Jujutsu implementation of the VCS protocol.

  This implementation uses Jujutsu (jj) commands to manage PR stacks,
  leveraging jj's change-based model and powerful revset queries."
  (:refer-clojure :exclude [parents])
  (:require
    [bb-tty.ansi :as ansi]
    [clojure.string :as str]
    [prstack.utils :as u]
    [prstack.vcs.graph :as graph]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration

(defn detect-trunk-branch
  "Detects wether the trunk branch is named 'master' or 'main'"
  []
  (first
    (into
      []
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
  {:vcs-config/trunk-branch
   ;; Check this out: replacing with "feature-base" brings 'main' in a branch
   ;; with strange ordering. It seems that 'ensure-trunk-branch' is not very
   ;; flexible.
   (detect-trunk-branch!)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Basic operations

(defn push-branch [branch-name]
  (u/run-cmd ["jj" "git" "push" "-b" branch-name "--allow-new"]
    {:echo? true}))

(defn find-megamerge [start-ref]
  (-> (u/run-cmd ["jj" "log" "--no-graph"
                  "-r"
                  (format "%s & ancestors(%s) & merges()"
                    ;; Don't go further than the fork point
                    (format "fork_point(trunk() | %s)..%s" start-ref start-ref)
                    start-ref)
                  "-T" "change_id.short()"])
    (str/trim)
    (not-empty)))

(comment
  (find-megamerge "@"))

(defn- ^:lsp/allow-unused parents [ref]
  (->> (u/run-cmd ["jj" "log" "--no-graph"
                   "-r" (format "parents(%s)" ref)
                   "-T" "separate(';', change_id.short(), local_bookmarks, remote_bookmarks) ++ '\n'"])
    (str/split-lines)
    (map #(str/split % #";"))
    (map #(zipmap [:change/change-id :change/local-branches :change/remote-branches] %))
    (map #(update % :change/local-branches
            (fn [bm] (str/split bm #" "))))
    (map #(update % :change/remote-branches
            (fn [bm] (str/split bm #" "))))))

(defn trunk-moved? [{:vcs-config/keys [trunk-branch]}]
  (let [local-trunk-ref (u/run-cmd ["jj" "log" "--no-graph"
                                    "-r" "fork_point(trunk() | @)"
                                    "-T" "commit_id"])
        remote-trunk-ref (u/run-cmd ["jj" "log" "--no-graph"
                                     "-r" (str trunk-branch "@origin")
                                     "-T" "commit_id"])]
    (println (ansi/colorize :yellow "\nChecking if trunk moved"))
    (println (ansi/colorize :cyan (str "local " trunk-branch)) local-trunk-ref)
    (println (ansi/colorize :cyan (str "remote " trunk-branch)) remote-trunk-ref)
    (not= local-trunk-ref remote-trunk-ref)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Change data structure

(defn- remove-asterisk-from-branch-name [branch-name]
  (when branch-name
    (str/replace branch-name #"\*$" "")))

(defn local-branchname [change]
  (remove-asterisk-from-branch-name
    (first (:change/local-branches change))))

(defn remote-branchname [change]
  (remove-asterisk-from-branch-name
    (u/find-first
      #(not (str/ends-with? % "@git"))
      (:change/remote-branches change))))

(def ^:lsp/allow-unused Leaf
  [:map
   [:change/local-branches [:vector :string]]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph operations

(defn- parse-graph-output
  "Parses jj log output into a collection of node maps."
  [output _trunk-branch]
  (when (not-empty output)
    (into []
      (comp
        (map str/trim)
        (remove empty?)
        (map #(str/split % #";"))
        (map (fn [[change-id commit-sha parents-str local-branches-str remote-branches-str]]
               {:node/change-id change-id
                :node/commit-sha commit-sha
                :node/parents (if (empty? parents-str)
                                []
                                (str/split parents-str #" "))
                :node/local-branches (if (empty? local-branches-str)
                                       []
                                       (str/split local-branches-str #" "))
                :node/remote-branches (if (empty? remote-branches-str)
                                        []
                                        (str/split remote-branches-str #" "))})))
      (str/split-lines output))))

(defn read-graph
  "Reads the full VCS graph from jujutsu.

  Reads all commits from trunk to all bookmark heads, building a complete
  graph representation with parent/child relationships.

  Returns a Graph (see prstack.vcs.graph/Graph)"
  [{:vcs-config/keys [trunk-branch]}]
  (let [;; Get trunk change-id
        trunk-change-id (str/trim
                          (u/run-cmd
                            ["jj" "log" "--no-graph" "-r" trunk-branch
                             "-T" "change_id.short()"]))
        ;; Get all changes from trunk to all bookmark heads (inclusive)
        ;; Include all intermediate changes, not just bookmarked ones
        revset (format "ancestors(bookmarks()) & %s::" trunk-branch)
        output (u/run-cmd
                 ["jj" "log" "--no-graph"
                  "-r" revset
                  "-T" (str "separate(';', "
                            "change_id.short(), "
                            "commit_id, "
                            "parents.map(|p| p.change_id().short()).join(' '), "
                            "local_bookmarks.join(' '), "
                            "remote_bookmarks.join(' ')) "
                            "++ \"\\n\"")])
        nodes (parse-graph-output output trunk-branch)]
    (graph/build-graph nodes trunk-change-id)))

(defn current-change-id
  "Returns the change-id of the current working copy (@)."
  []
  (str/trim (u/run-cmd ["jj" "log" "--no-graph" "-r" "@" "-T" "change_id.short()"])))

(defn read-current-stack-graph
  "Reads a graph specifically for the current working copy stack.

  This includes all changes from trunk to @, even if @ is not bookmarked.

  Returns a Graph (see prstack.vcs.graph/Graph)"
  [{:vcs-config/keys [trunk-branch]}]
  (let [;; Get trunk change-id
        trunk-change-id (str/trim
                          (u/run-cmd
                            ["jj" "log" "--no-graph" "-r" trunk-branch
                             "-T" "change_id.short()"]))
        ;; Get all changes from fork point to current, including unbookmarked
        revset "fork_point(trunk() | @)::@"
        output (u/run-cmd
                 ["jj" "log" "--no-graph"
                  "-r" revset
                  "-T" (str "separate(';', "
                            "change_id.short(), "
                            "commit_id, "
                            "parents.map(|p| p.change_id().short()).join(' '), "
                            "local_bookmarks.join(' '), "
                            "remote_bookmarks.join(' ')) "
                            "++ \"\\n\"")])
        nodes (parse-graph-output output trunk-branch)]
    (graph/build-graph nodes trunk-change-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API

(comment
  (def graph* (read-graph (config)))
  (tap> graph*)
  (graph/find-all-paths-to-trunk graph* "wmkwotut")
  (detect-trunk-branch!)
  (config))
