(ns prstack.tools.schema
  "A namespace with a few utils for working with Malli-like schemas. This
  is done to not need to depend on Malli for basic secification.")

(defn merge [schema1 schema2]
  (into [:map]
   (concat (rest schema1) (rest schema2))))

(comment
  (merge
    [:map [:name :string]]
    [:map [:age :int] [:created-at :date]]))
