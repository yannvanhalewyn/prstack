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

(defn get-stack-command [ref]
  ["jj" "log" "--no-graph"
   "-r" (format "fork_point(trunk() | %s)::%s & bookmarks()" ref ref)
   "-T" "local_bookmarks ++ \"\n\""])

(defn- ensure-trunk-bookmark
  "Ensure the stack starts with the trunk bookmark. Sometimes the trunk
  bookmark has moved and is not included in the stack output"
  [{:vcs-config/keys [trunk-bookmark]} stack]
  (if (= (str/replace (first stack) #"\*" "") trunk-bookmark)
    stack
    (into [trunk-bookmark] stack)))

(defn parse-stack
  [raw-output {:vcs-config/keys [trunk-bookmark] :as vcs-config}]
  (some->> raw-output
    (str/split-lines)
    (reverse)
    (into []
      (comp
        (map str/trim)
        (remove empty?)
        (remove #{trunk-bookmark})))
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
  (parse-stack "main"
    {:vcs-config/trunk-bookmark "main"})
  (get-stack (config)))

(def leaves-template
  (str "\"{"  "\" ++ \"\\n\" ++"
       "  \"\\\"commit-id\\\": \\\"\" ++ commit_id.short() ++ \"\\\",\" ++ \"\\n\" ++"
       "  \"\\\"change-id\\\": \\\"\" ++ change_id.short() ++ \"\\\",\" ++ \"\\n\" ++"
       "  \"\\\"bookmark-name\\\": \\\"\" ++ if(local_bookmarks, local_bookmarks.map(|b| b.name()).join(\",\"), \"\") ++ \"\\\",\" ++ \"\\n\" ++"
       "  \"\\\"description\\\": \" ++ description.first_line().escape_json() ++ \"\\n\" ++"
       "\"}\""))

(defn get-leaves [{:vcs-config/keys [trunk-bookmark]}]
  (into
    []
    (comp
      (map #(str/split % #"\;"))
      (map #(zipmap [:description :change-id :bookmarks] %))
      (map #(update % :bookmarks
              (fn [bm]
                (str/split bm #" ")))))
    (some->
      (u/run-cmd
        ["jj" "log" "--no-graph"
         "-r" (format "heads(bookmarks()) ~ %s" trunk-bookmark)
         "-T" "separate(';', description.first_line(), change_id.short(), local_bookmarks) ++ '\n'"])
      (not-empty)
      (str/split-lines))))

(comment
  (get-leaves (config)))

(defn trunk-moved? [{:vcs-config/keys [trunk-bookmark] :as x}]
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
