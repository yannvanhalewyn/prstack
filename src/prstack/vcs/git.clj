(ns prstack.vcs.git
  "Git implementation of the VCS protocol.

  This implementation provides Git-based version control operations that are
  designed to be a drop-in replacement for the Jujutsu implementation. It uses
  standard git commands to manage PR stacks."
  (:refer-clojure :exclude [parents])
  (:require
    [clojure.string :as str]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Branch Operations

(defn fetch! [vcs]
  (u/run-cmd ["git" "fetch" "origin"]
    {:echo? true :dir (:vcs/project-dir vcs)}))

(defn set-bookmark-to-remote! [vcs branch-name]
  (u/run-cmd ["git" "branch" "-f" branch-name (str "origin/" branch-name)]
    {:echo? true :dir (:vcs/project-dir vcs)}))

(defn delete-bookmark! [vcs branch-name]
  (u/run-cmd ["git" "branch" "-D" branch-name]
    {:dir (:vcs/project-dir vcs) :echo? true}))

(defn rebase-on! [vcs target-ref]
  (u/run-cmd ["git" "rebase" target-ref]
    {:dir (:vcs/project-dir vcs) :echo? true}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration

(defn- detect-trunk-branch
  "Detects whether the trunk branch is named 'master' or 'main'.

  Looks at local branches to determine which is the trunk."
  [vcs]
  (let [branches (str/split-lines
                   (u/run-cmd ["git" "branch" "--list" "master" "main"]
                     {:dir (:vcs/project-dir vcs)}))]
    (first
      (into []
        (comp
          (map str/trim)
          (map #(str/replace % #"^[*+]\s+" ""))
          (filter #{"master" "main"}))
        branches))))

(defn detect-trunk-branch! [vcs]
  (or (detect-trunk-branch vcs)
      (throw (ex-info "Could not detect trunk branch" {}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Querying commit log

(defn commit-sha
  "Returns the commit SHA for a given ref."
  [vcs ref]
  (str/trim (u/run-cmd ["git" "rev-parse" ref]
              {:dir (:vcs/project-dir vcs)})))

(defn merge-base
  "Finds the merge base (common ancestor) between two refs."
  [ref1 ref2 opts]
  (str/trim (u/run-cmd ["git" "merge-base" ref1 ref2] opts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parsing Branche Info

;; Example line:
;; docs-prod+++5c090cb3+++(refactor): remove `:node/` namespace in favor of `:change/`
(defn- parse-branch-info [branch-line]
  (when-let [[branchname sha subject] (str/split branch-line #"\+\+\+" 3)]
    (let [[_ local-branchname]
          (re-find #"heads/(.*)" branchname)
          [_ remote-name remote-branchname]
          (re-find #"remotes/(.*)/(.*)" branchname)]
      (cond->
        {:branch/name (or local-branchname remote-branchname)
         :branch/commit-sha sha
         :branch/title subject}
        remote-name (assoc :branch/remote remote-name)))))

(defn- remote-branch? [branch]
  (contains? branch :branch/remote))

(defn- get-parent-shas
  "Returns the parent commit SHAs for a given commit."
  [vcs sha]
  (let [output (u/run-cmd ["git" "rev-list" "--parents" "-n" "1" sha]
                 {:dir (:vcs/project-dir vcs)})
        parts (str/split (str/trim output) #"\s+")]
    ;; First part is the commit itself, rest are parents
    (into [] (rest parts))))

(defn- get-branches [vcs]
  (->>
    (u/run-cmd ["git" "branch" "-v" "--all"
                "--format" "%(refname:lstrip=1)+++%(objectname)+++%(subject)"]
      {:dir (:vcs/project-dir vcs)})
    (str/split-lines)
    (into []
      (comp
        (map str/trim)
        ;; (HEAD detached at ...)
        (remove #(str/starts-with? % "("))
        (keep parse-branch-info)))))

(comment
  (parse-branch-info
    "heads/docs/add-workflows-and-diagrams+++7534a572+++(feat): add code example component")
  (map :branch/name (get-branches {:vcs/project-dir "tmp/parallel-branches"})))

(defn get-commits-between
  "Gets all commit SHAs between trunk and the given ref (inclusive).

  Returns commits in topological order (oldest to newest)."
  [vcs trunk-branch ref]
  (let [output (u/run-cmd ["git" "rev-list" "--topo-order" "--reverse"
                           (str trunk-branch ".." ref)]
                 {:dir (:vcs/project-dir vcs)})]
    (into []
      (comp
        (map str/trim)
        (remove empty?))
      (str/split-lines output))))

(defn- parse-log-line
  "Parses a single line from git log output.
  Format: SHA+++PARENTS
  Returns nil for invalid lines."
  [line]
  (when-not (str/blank? line)
    (let [[sha parents] (str/split line #"\+\+\+" 2)]
      (when (and sha (not (str/blank? sha)))
        {:sha sha
         :parent-shas (if (or (nil? parents) (str/blank? parents))
                        []
                        (str/split parents #" "))}))))

(defn get-all-commits-from-log
  "Gets all commits reachable from any branch but not from trunk.
  Uses git log with --branches --remotes to include remote branches that may be
  ahead of local branches. Returns a map of commit SHA to parsed commit info."
  [vcs]
  (let [trunk-branch (vcs/trunk-branch vcs)
        output (u/run-cmd ["git" "log" "--branches" "--remotes"
                           "--not" trunk-branch
                           "--format=%H+++%P"
                           "--topo-order"]
                 {:dir (:vcs/project-dir vcs)})]
    (->> output
      str/split-lines
      (keep parse-log-line)
      (reduce
        (fn [acc {:keys [sha parent-shas]}]
          (if (contains? acc sha)
            acc  ;; Already seen this commit, skip
            (assoc acc sha {:sha sha :parent-shas parent-shas})))
        {}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph operations

(defn- parse-change
  "Builds a node map from a commit SHA."
  [sha vcs trunk-sha branch-idx]
  (let [parents (get-parent-shas vcs sha)]
    {:change/change-id sha
     :change/commit-sha sha
     :change/parent-ids parents
     :change/local-branchnames (->> (get branch-idx sha)
                                 (remove remote-branch?)
                                 (map :branch/name))
     :change/remote-branchnames (->> (get branch-idx sha)
                                  (filter remote-branch?)
                                  (map :branch/name))
     :change/trunk-node? (= sha trunk-sha)}))

(defn- build-change-from-log-entry
  "Builds a change map from a parsed log entry and branch index.
  Returns nil for entries with blank sha."
  [log-entry branch-idx trunk-sha]
  (when-let [sha (not-empty (:sha log-entry))]
    (let [branches-at-sha (get branch-idx sha [])
          local-branches (->> branches-at-sha
                           (remove remote-branch?)
                           (map :branch/name)
                           vec)
          remote-branches (->> branches-at-sha
                            (filter remote-branch?)
                            (map :branch/name)
                            vec)]
      {:change/change-id sha
       :change/commit-sha sha
       :change/parent-ids (:parent-shas log-entry)
       :change/local-branchnames local-branches
       :change/remote-branchnames remote-branches
       :change/trunk-node? (= sha trunk-sha)})))

(defn- build-trunk-change
  "Builds the trunk change node."
  [trunk-sha vcs branch-idx]
  (let [parents (get-parent-shas vcs trunk-sha)
        branches-at-sha (get branch-idx trunk-sha [])]
    {:change/change-id trunk-sha
     :change/commit-sha trunk-sha
     :change/parent-ids parents
     :change/local-branchnames (->> branches-at-sha
                                 (remove remote-branch?)
                                 (map :branch/name)
                                 vec)
     :change/remote-branchnames (->> branches-at-sha
                                  (filter remote-branch?)
                                  (map :branch/name)
                                  vec)
     :change/trunk-node? true}))

(defn parse-graph-commits
  "Parses a collection of commit SHAs into Change maps."
  [vcs commit-shas trunk-sha]
  (let [branch-idx (group-by :branch/commit-sha (get-branches vcs))]
    (into []
      (map #(parse-change % vcs trunk-sha branch-idx))
      commit-shas)))

(defn build-changes-from-log
  "Builds change maps from git log output and branch index.

  Combines commit info from git log with branch pointer info from get-branches.
  Filters out any changes with blank change-id to prevent downstream errors."
  [vcs log-entries trunk-sha branch-idx]
  (let [trunk-change (build-trunk-change trunk-sha vcs branch-idx)
        other-changes (into []
                        (keep #(build-change-from-log-entry % branch-idx trunk-sha))
                        (vals log-entries))]
    (->> (conj other-changes trunk-change)
      (remove #(str/blank? (:change/change-id %)))
      vec)))

(defn- build-change-from-sha
  "Builds a change map for a single commit SHA.
  Used to include commits not captured by git log --branches --not trunk,
  such as the fork-point when trunk has advanced."
  [vcs sha branch-idx]
  (let [parents (get-parent-shas vcs sha)
        branches-at-sha (get branch-idx sha [])]
    {:change/change-id sha
     :change/commit-sha sha
     :change/parent-ids parents
     :change/local-branchnames (->> branches-at-sha
                                 (remove remote-branch?)
                                 (map :branch/name)
                                 vec)
     :change/remote-branchnames (->> branches-at-sha
                                  (filter remote-branch?)
                                  (map :branch/name)
                                  vec)
     :change/trunk-node? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VCS Implementation

(defn- find-fork-point* [vcs ref]
  (let [opts {:dir (:vcs/project-dir vcs)}]
    (merge-base ref (vcs/trunk-branch vcs) opts)))

(defrecord GitVCS []
  vcs/VCS
  (read-vcs-config [this]
    {:vcs-config/trunk-branch (detect-trunk-branch! this)})

  (push-branch! [this branch-name]
    (u/run-cmd ["git" "push" "-u" "origin" branch-name "--force-with-lease"]
      {:echo? true :dir (:vcs/project-dir this)}))

  (read-relevant-changes [this]
    (let [trunk-sha (commit-sha this (vcs/trunk-branch this))
          log-entries (get-all-commits-from-log this)
          branches (get-branches this)
          branch-idx (group-by :branch/commit-sha branches)
          nodes (build-changes-from-log this log-entries trunk-sha branch-idx)
          ;; Get fork-points for all local branches to ensure get-all-stacks works
          local-branches (->> branches
                           (remove remote-branch?)
                           (map :branch/name))
          fork-point-shas (into #{}
                            (comp
                              (map #(find-fork-point* this %))
                              (filter some?))
                            local-branches)
          ;; Include any fork-points that aren't already in the graph
          missing-fork-points (remove #(or (= % trunk-sha)
                                           (contains? log-entries %))
                                fork-point-shas)
          fork-point-changes (map #(build-change-from-sha this % branch-idx)
                               missing-fork-points)
          nodes (into nodes fork-point-changes)]
      {:nodes nodes
       :trunk-change-id trunk-sha}))

  (current-change-id [this]
    (commit-sha this "HEAD"))

  (find-fork-point [this ref]
    (find-fork-point* this ref))

  (fetch! [this]
    (fetch! this))

  (set-bookmark-to-remote! [this branch-name]
    (set-bookmark-to-remote! this branch-name))

  (delete-bookmark! [this bookmark-name]
    (delete-bookmark! this bookmark-name))

  (rebase-on! [this target-ref]
    (rebase-on! this target-ref)))
