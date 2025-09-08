(ns prstack.config
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io])
  (:import
    (java.io PushbackReader)))

(defn read-local []
  (->
   (let [file (io/file ".prstack/config.edn")]
     (when (.exists file)
       (with-open [rdr (io/reader file)]
         (edn/read (PushbackReader. rdr)))))
   (update :ignored-bookmarks set)))

(comment
  (read-local))
