(ns com.nexus-quant.ark-engine.ingestor.unit-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.nexus-quant.ark-engine.ingestor.core :as sut]
            [cheshire.core :as json]))

(deftest safe-parse-test
  (testing "Parsing valid JSON strings"
    (let [redis-fields ["data" (json/generate-string {"type" "candle" "c" "100"})]
          result (#'sut/safe-parse redis-fields)]

      (is (= :ok (:status result)))
      ;; json/parse-string with true flag keywordizes keys, so "c" becomes :c
      (is (= "100" (:c (:data (:payload result)))))))

  (testing "Handling malformed JSON strings"
    (let [poison-fields ["data" "{bad-json"]
          result (#'sut/safe-parse poison-fields)]

      (is (= :error (:status result)))
      (is (= :malformed-json (:reason result)))
      (is (instance? Exception (:ex result)))))

  (testing "Idempotency: Handling pre-parsed maps"
    ;; Useful if manual injection sends maps directly
    (let [map-fields ["data" {:type :signal :id 1}]
          result (#'sut/safe-parse map-fields)]

      (is (= :ok (:status result)))
      (is (= 1 (:id (:data (:payload result))))))))