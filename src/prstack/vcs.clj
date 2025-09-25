(ns prstack.vcs
  (:require
    [babashka.process :as p]
    [clojure.string :as str]
    [prstack.utils :as u]))

(defn detect-trunk-bookmark
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

(defn detect-trunk-bookmark! []
  (or (detect-trunk-bookmark)
      (throw (ex-info "Could not detect trunk bookmark" {}))))

(comment (detect-trunk-bookmark!))

(defn push-branch [branch-name]
  (u/run-cmd ["jj" "git" "push" "-b" branch-name "--allow-new"]
    {:echo? true}))

(defn find-megamerge [start-ref]
  (-> (u/run-cmd ["jj" "log" "--no-graph"
                  "-r" (format "ancestors(%s) & merges()" start-ref)
                  "-T" "change_id.short()"]
        {:dir ",local/test-repo"})
    (str/trim)
    (not-empty)))

(defn parents [ref]
  (->> (u/run-cmd ["jj" "log" "--no-graph"
                   "-r" (format "parents(%s)" ref)
                   "-T" "separate(';', change_id.short(), local_bookmarks, remote_bookmarks) ++ '\n'"]
         {:dir ",local/test-repo"})
    (str/split-lines)
    (map #(str/split % #";"))
    (map #(zipmap [:change/change-id :change/local-bookmarks :change/remote-bookmarks] %))))

(comment
  (for [change (some-> (find-megamerge "@") parents)]
    (get-stack (:change/change-id change))))

(defn get-stack-command [ref]
  ["jj" "log" "--no-graph"
   "-r" (format "fork_point(trunk() | %s)::%s & bookmarks()" ref ref)
   "-T" "separate(';', change_id.short(), commit_id, local_bookmarks, remote_bookmarks) ++ \"\n\""])

(comment
  (u/run-cmd (get-stack-command "@")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Change

(def Change
  [:map
   [:change/description :string]
   [:change/change-id :string]
   [:change/commit-sha :string]
   [:change/local-bookmarks [:sequential :string]]
   [:change/remote-bookmarks [:sequential :string]]])

(defn- remove-asterisk-from-branch-name [branch-name]
  (when branch-name
    (str/replace branch-name #"\*$" "")))

(defn local-branchname [change]
  (remove-asterisk-from-branch-name
    (first (:change/local-bookmarks change))))

(defn remote-branchname [change]
  (remove-asterisk-from-branch-name
    (u/find-first
      #(not (str/ends-with? % "@git"))
      (:change/remote-bookmarks change))))

(def ^:lsp/allow-unused Leaf
  [:map
   [:change/local-bookmarks [:vector :string]]])

;; TODO align this parser with 'parse-change'
(defn get-leaves [{:vcs-config/keys [trunk-bookmark]}]
  (into
    []
    (comp
      (map #(zipmap [:change/description
                     :change/change-id
                     :change/commit-sha
                     :change/local-bookmarks]
              (str/split % #"\;")))
      (map #(update % :change/local-bookmarks
              (fn [bm]
                (str/split bm #" ")))))
    (some->
      (u/run-cmd
        ["jj" "log" "--no-graph"
         "-r" (format "heads(bookmarks()) ~ %s" trunk-bookmark)
         "-T" "separate(';', description.first_line(), change_id.short(), commit_id, local_bookmarks) ++ '\n'"])
      (not-empty)
      (str/split-lines))))

(comment
  (get-leaves (config)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stack

(def ^:lsp/allow-unused Stack
  [:vector Change])

(defn- ensure-trunk-bookmark
  "Ensure the stack starts with the trunk bookmark. Sometimes the trunk
  bookmark has moved and is not included in the stack output"
  [{:vcs-config/keys [trunk-bookmark]} stack]
  (if (= (str/replace (local-branchname (first stack)) #"\*" "") trunk-bookmark)
    stack
    (into [{:change/local-bookmarks [trunk-bookmark]
            :change/remote-bookmarks [(str trunk-bookmark "@origin")]}] stack)))

(defn- parse-change [raw-line]
  (->
    (zipmap [:change/change-id
             :change/commit-sha
             :change/local-bookmarks
             :change/remote-bookmarks]
      (str/split raw-line #";"))
    (update :change/local-bookmarks #(str/split % #" "))
    (update :change/remote-bookmarks #(str/split % #" "))))

(defn parse-stack
  [raw-output {:vcs-config/keys [trunk-bookmark] :as vcs-config}]
  (some->> raw-output
    (str/split-lines)
    (reverse)
    (into []
      (comp
        ;;(map str/trim)
        ;;(remove empty?)
        (map parse-change)
        (remove #(= (local-branchname %) trunk-bookmark))))
    (not-empty)
    (ensure-trunk-bookmark vcs-config)))

(defn get-stack
  ([vcs-config]
   (get-stack "@" vcs-config))
  ([ref vcs-config]
   (parse-stack
     (u/run-cmd (get-stack-command ref))
     vcs-config)))

(comment
  (str/split-lines
    (u/run-cmd (get-stack-command "@")
      {:dir ",local/test-repo"})))

(comment
  (parse-stack (u/run-cmd (get-stack-command "test-bookmark"))
    {:vcs-config/trunk-bookmark "main"})
  (get-stack (config)))

(defn trunk-moved? [{:vcs-config/keys [trunk-bookmark]}]
  (let [local-trunk-ref (u/run-cmd ["jj" "log" "--no-graph"
                                    "-r" "fork_point(trunk() | @)"
                                    "-T" "commit_id"])
        remote-trunk-ref (u/run-cmd ["jj" "log" "--no-graph"
                                     "-r" (str trunk-bookmark "@origin")
                                     "-T" "commit_id"])]
    (println (u/colorize :yellow "\nChecking if trunk moved"))
    (println (u/colorize :cyan (str "local " trunk-bookmark)) local-trunk-ref)
    (println (u/colorize :cyan (str "remote " trunk-bookmark)) remote-trunk-ref)
    (not= local-trunk-ref remote-trunk-ref)))

(defn create-pr! [head-branch base-branch]
  (->
    (p/shell {:inherit true}
      "gh" "pr" "create" "--head" head-branch "--base" base-branch)
    p/check
    :out
    slurp))

(defn find-pr
  [head-branch base-branch]
  ;;(println :find-pr head-branch base-branch)
  (spit "target/dev.log" (str "Fetching PR for " head-branch " onto " base-branch) :append true)
  (not-empty
    (u/run-cmd ["gh" "pr" "list"
                "--head" head-branch
                "--base" base-branch
                "--limit" "1"
                "--json" "url" "--jq" ".[0].url"])))

(defn config
  "Reads the VCS configuration"
  []
  {:vcs-config/trunk-bookmark (detect-trunk-bookmark!)})

(comment
  (config))
