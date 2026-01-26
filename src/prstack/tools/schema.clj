(ns prstack.tools.schema
  "A namespace with a few utils for working with Malli-like schemas. This
  is done to not need to depend on Malli for basic secification.")

(defn entries [schema]
  (rest schema))

(defn keys [schema]
  (map first (entries schema)))

(defn key [schema-map-entry]
  (first schema-map-entry))

(defn properties [schema-map-entry]
  (second schema-map-entry))

(defn merge [schema1 schema2]
  (into [:map]
   (concat (rest schema1) (rest schema2))))

(comment
  (properties [:name {:attr true} :string])
  (merge
    [:map [:name :string]]
    [:map [:age :int] [:created-at :date]]))
