(ns com.nexus-quant.ark-engine.strategy-engine.unit-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.nexus-quant.ark-engine.strategy-engine.indicators :as sut]
            [tick.core :as t]))

(defn mock-candle [price i]
  {:close (bigdec price)
   :open (bigdec price) :high (bigdec price) :low (bigdec price) :volume 100M
   :timestamp (t/<< (t/now) (t/new-duration i :minutes))})

(deftest ema-calculation-test
  (testing "EMA 3 should be weighted towards recent prices"
    ;; Series: 10, 11, 12, 20 (Jump at the end)
    ;; Candles chronological order: t-3 (10), t-2 (11), t-1 (12), t-0 (20)
    (let [candles [(mock-candle 10 3)
                   (mock-candle 11 2)
                   (mock-candle 12 1)
                   (mock-candle 20 0)]
          val (sut/ema candles 3)]

      ;; EMA reacts fast. SMA would be (11+12+20)/3 = 14.3
      ;; EMA should be greater than previous simple average
      (is (> val 14.0M))
      (is (< val 20.0M)))))
