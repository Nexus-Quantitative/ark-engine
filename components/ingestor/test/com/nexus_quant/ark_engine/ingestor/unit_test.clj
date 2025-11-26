(ns com.nexus-quant.ark-engine.ingestor.unit-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.nexus-quant.ark-engine.ingestor.core :as sut]))

(deftest safe-parse-test
  (testing "Parsing valid EDN strings"
    (let [redis-fields ["data" "{:type :candle :c 100}"]
          ;; Accessing the private function via Var if necessary, or assuming public
          result (#'sut/safe-parse redis-fields)]

      (is (= :ok (:status result)))
      (is (= 100 (:c (:data (:payload result)))))))

  (testing "Handling malformed EDN strings"
    (let [poison-fields ["data" "{:bad-syntax"]
          result (#'sut/safe-parse poison-fields)]

      (is (= :error (:status result)))
      (is (= :malformed-edn (:reason result)))
      (is (instance? Exception (:ex result)))))

  (testing "Idempotency: Handling pre-parsed maps"
    ;; Useful if manual injection sends maps directly
    (let [map-fields ["data" {:type :signal :id 1}]
          result (#'sut/safe-parse map-fields)]

      (is (= :ok (:status result)))
      (is (= 1 (:id (:data (:payload result))))))))