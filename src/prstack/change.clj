(ns prstack.change)

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
