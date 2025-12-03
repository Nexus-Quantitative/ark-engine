(ns com.nexus-quant.ark-engine.domain-model.interface-test
  (:require [clojure.test :refer :all]
            [com.nexus-quant.ark-engine.domain-model.interface :as sut]))

(deftest coercion-test
  (testing "Candle Coercion (Strict)"
    (let [raw {:type "candle"
               :symbol "BTC/USDT" :timeframe "1m"
               :open "100.0" :high "105.0" :low "99.0" :close "101.0" ;; Full OHLC
               :volume "500"
               :timestamp "2025-01-01T00:00:00Z"}
          res (sut/coerce-candle raw)]
      (is (= 101.0M (:close res)))
      (is (= :candle (:type res)))))

  (testing "Tick Coercion"
    (let [raw {:type "tick"
               :symbol "ETH/USDT"
               :price "3000.50"
               :volume "1.5"
               :timestamp 1700000000000}
          res (sut/coerce-tick raw)]
      (is (= 3000.50M (:price res)))
      (is (= :tick (:type res)))))

  (testing "REJECTION: Floating Point Safety"
    (let [unsafe-input {:type "tick"
                        :symbol "BTC/USDT"
                        :price 100.50 ;; Double (Danger!)
                        :volume "1"
                        :timestamp 1700000000000}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Tick Coercion Failed"
                            (sut/coerce-tick unsafe-input))
          "Must reject Double inputs to prevent IEEE 754 precision loss"))))
