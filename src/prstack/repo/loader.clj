(ns prstack.repo.loader
  "Loads a repo and the data read through it in the background: its graph,
  stacks and PRs. Every piece of data is held in a load map, see
  `load-schema`, so it can be displayed while it's still loading.

  The loader holds no state. Results are written with the `update!` function
  passed by the caller, which applies a function to the loading repo, so the
  caller decides where the repo lives and which results to keep."
  (:require
    [prstack.remote :as remote]
    [prstack.repo :as repo]
    [prstack.stack :as stack]
    [prstack.utils :as u]
    [prstack.vcs :as vcs]))

(defn- load-schema
  "Data that is loaded in the background. While reloading, the previous result
  is kept so it can still be displayed."
  [result-schema]
  [:map
   [:load/status [:enum :status/idle :status/pending :status/failed :status/success]]
   [:load/result {:optional true} result-schema]
   [:load/error {:optional true}
    [:map
     [:error/type {:optional true} :keyword]
     [:error/message :string]]]])

(def RepoLoad
  "The data loaded for a repo, besides the repo itself."
  [:map
   [:repo/graph (load-schema :map)]
   [:repo/current-stacks (load-schema [:sequential stack/Stack])]
   ;; Only loaded on request, see `load-all-stacks!`
   [:repo/all-stacks (load-schema [:sequential stack/Stack])]
   [:repo/prs (load-schema [:sequential remote/PR])]])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Load maps

(defn- load-status [load-map]
  (:load/status load-map))

(defn- succeeded [result]
  {:load/status :status/success :load/result result})

(defn- failed [error]
  {:load/status :status/failed :load/error error})

(defn- with-status
  "Sets the status of a load, keeping a previous result."
  [load-map status]
  (-> load-map
    (assoc :load/status status)
    (dissoc :load/error)))

(defn- run-load
  "Calls `f` and returns a load map with its result, or with its error when it
  throws."
  [f]
  (try
    (succeeded (f))
    (catch Throwable e
      (failed {:error/message (u/exception-message e)}))))

(defn- fetch-prs [repo]
  (let [[result err] (try
                       (remote/list-prs (:repo/remote repo) {})
                       (catch Throwable e
                         [nil {:error/message (u/exception-message e)}]))]
    (if err
      (failed err)
      (succeeded result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API

(defn mark-loading
  "Marks `repo` as loading. Previous stacks are kept while reloading, so they
  can still be displayed."
  [repo]
  (assoc repo
    :repo/graph (with-status (:repo/graph repo) :status/pending)
    :repo/current-stacks (with-status (:repo/current-stacks repo) :status/pending)
    :repo/all-stacks (with-status (:repo/all-stacks repo) :status/idle)
    ;; Previous PRs are not kept, so it shows they're being fetched
    :repo/prs {:load/status :status/pending}))

(defn needs-all-stacks?
  "Returns whether all stacks of `repo` can be loaded but are not loaded or
  loading yet. They can be loaded once its graph is loaded."
  [repo]
  (and (= :status/success (load-status (:repo/graph repo)))
       (= :status/idle (load-status (:repo/all-stacks repo)))))

(defn mark-loading-all-stacks
  "Marks all stacks of `repo` as loading, see `load-all-stacks!`."
  [repo]
  (update repo :repo/all-stacks with-status :status/pending))

(defn load-all-stacks!
  "Loads all stacks of `repo` in the background, from its loaded graph."
  [repo update!]
  (let [vcs-graph (get-in repo [:repo/graph :load/result])]
    (u/start-large-stack-thread!
      (fn []
        (update! assoc :repo/all-stacks
          (run-load #(stack/get-all-stacks repo vcs-graph)))))))

(defn load!
  "Opens the repo with `repo-id` and loads its graph, current stacks and PRs in
  the background. PRs are fetched in parallel as soon as the repo is opened.

  Results are written with `(update! f & args)`, which applies `f` to the
  loading repo. The repo's clients are only present once it's opened. The
  repo's name is not written, so the caller decides how it is displayed.
  `on-graph-loaded` is called once the graph and current stacks are loaded,
  so all stacks can be loaded, see `load-all-stacks!`."
  [repo-id {:keys [update! on-graph-loaded]}]
  (u/start-large-stack-thread!
    (fn []
      (let [opened (run-load #(repo/open repo-id))]
        (if (= :status/failed (load-status opened))
          (update! assoc
            :repo/graph opened
            :repo/current-stacks opened
            :repo/all-stacks opened
            :repo/prs opened)
          (let [repo (dissoc (:load/result opened) :repo/name)]
            (update! merge repo)
            (u/start-large-stack-thread!
              #(update! assoc :repo/prs (fetch-prs repo)))
            (let [vcs-graph (run-load #(vcs/read-graph (:repo/vcs repo)
                                         (:repo/local-config repo)))]
              (if (= :status/failed (load-status vcs-graph))
                (update! assoc
                  :repo/graph vcs-graph
                  :repo/current-stacks vcs-graph
                  :repo/all-stacks vcs-graph)
                (let [current-stacks (run-load #(stack/get-current-stacks repo
                                                  (:load/result vcs-graph)))]
                  (update! assoc
                    :repo/graph vcs-graph
                    :repo/current-stacks current-stacks)
                  (when on-graph-loaded
                    (on-graph-loaded)))))))))))
