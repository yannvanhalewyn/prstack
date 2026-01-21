(ns prstack.config
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io])
  (:import
    (java.io PushbackReader)))

(defn read-local []
  (let [config (let [file (io/file ".prstack/config.edn")]
                 (when (.exists file)
                   (with-open [rdr (io/reader file)]
                     (edn/read (PushbackReader. rdr)))))]
    (-> (or config {})
        (update :ignored-branches #(set (or % [])))
        (update :feature-base-branches #(set (or % []))))))

(comment
  (read-local))
