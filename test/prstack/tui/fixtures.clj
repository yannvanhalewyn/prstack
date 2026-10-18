(ns prstack.tui.fixtures
  "Builders for app states in TUI tests, see `prstack.tui.db/AppState`.")

(defn change [branch type]
  {:change/change-id branch
   :change/selected-branchname branch
   :change/type type})

(defn stack [& branches]
  (into [(change (first branches) :trunk)]
    (map #(change % :regular))
    (rest branches)))

(defn new-pr [number head base]
  {:pr/number number :pr/head-branch head :pr/base-branch base
   :pr/url (str "https://example.com/" number)})

(defn- loaded [result]
  {:load/status :status/success :load/result result})

(defn repo-state [id stacks prs]
  {:repo/id id
   :repo/name id
   :repo/load-token 1
   :repo/current-stacks (loaded stacks)
   :repo/all-stacks (loaded stacks)
   :repo/prs (loaded prs)})

(defn app-state [& repos]
  {:app-state/selected-tab 0
   :app-state/repos (into {} (map (juxt :repo/id identity)) repos)})
