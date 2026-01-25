(ns prstack.config
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.pprint :as pp])
  (:import
    (java.io PushbackReader)))

(def Config
  [:map
   [:vcs :keyword]
   [:ignored-branches :set :string]
   [:feature-base-branches :set :string]])

(defn read-local []
  (let [config (let [file (io/file ".prstack/config.edn")]
                 (when (.exists file)
                   (with-open [rdr (io/reader file)]
                     (edn/read (PushbackReader. rdr)))))]
    (-> (or config {})
      (update :ignored-branches #(set (or % [])))
      (update :feature-base-branches #(set (or % []))))))

(defn write-local [config]
  (let [file (io/file ".prstack/config.edn")
        dir (.getParentFile file)]
    ;; Create .prstack directory if it doesn't exist
    (when-not (.exists dir)
      (.mkdirs dir))
    ;; Convert sets to vectors for EDN serialization
    (let [config-to-write (-> config
                            (update :ignored-branches set)
                            (update :feature-base-branches set))]
      (with-open [wtr (io/writer file)]
        (pp/pprint config-to-write wtr)))))

(comment
  (read-local))
