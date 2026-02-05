(ns prstack.change
  (:require
    [prstack.utils :as u]))

(def Change
  [:map
   ;; Change info
   [:change/change-id :string]
   [:change/commit-sha :string]

   ;; Branch info
   [:change/local-branchnames [:sequential :string]]
   [:change/remote-branchnames [:sequential :string]]

   ;; Tree info
   [:change/parent-ids [:sequential :string]]
   [:change/trunk-node? :boolean]])

(defn remote-branchname
  "Tries to find the selected-branchname in the remote branchnames. Returns
  that or the first remote branchname."
  [change]
  (or
    (u/find-first #{(:change/selected-branchname change)}
      (:change/remote-branchnames change))
    (first (:change/remote-branchnames change))))
