(ns prstack.launch-test
  (:require
    [clojure.test :refer [deftest is]]
    [prstack.launch :as launch]))

(def ^:private supported-bb? #'launch/supported-bb?)

(deftest test-supported-bb?
  (is (false? (supported-bb? "1.12.214")))
  (is (false? (supported-bb? "1.12.217")))
  (is (true? (supported-bb? "1.12.218")))
  (is (true? (supported-bb? "1.12.218-SNAPSHOT")))
  (is (true? (supported-bb? "1.13.220")))
  (is (true? (supported-bb? "2.0.0")))
  (is (true? (supported-bb? nil)) "not on Babashka"))
