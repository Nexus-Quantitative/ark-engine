(ns com.nexus-quant.ark-engine.candle-aggregator.unit-test
  (:require [clojure.test :refer :all]
            [com.nexus-quant.ark-engine.candle-aggregator.core :as sut]
            [tick.core :as t]))

(defn mock-candle [price i]
  {:close (bigdec price) :open (bigdec price) :high (bigdec price) :low (bigdec price) :volume 10M
   :timestamp (t/<< (t/now) (t/new-duration i :minutes))})

(deftest aggregation-logic-test
  (testing "Deve acumular candles na mesma hora"
    ;; 14:00
    (let [base-ts (t/instant "2025-01-01T14:00:00Z")
          c1 {:timestamp base-ts :open 10M :high 12M :low 9M :close 11M :volume 100M}
          c2 {:timestamp (t/>> base-ts (t/minutes 1)) :open 11M :high 15M :low 11M :close 14M :volume 50M}

          ;; Passo 1: Iniciar
          step1 (sut/process-tick nil c1 "1h")
          state1 (:state step1)]

      (is (nil? (:closed step1)))
      (is (= 10M (:open state1)))

      ;; Passo 2: Merge
      (let [step2 (sut/process-tick state1 c2 "1h")
            state2 (:state step2)]
        (is (nil? (:closed step2)))
        (is (= 15M (:high state2))) ;; Max(12, 15)
        (is (= 9M  (:low state2)))  ;; Min(9, 11)
        (is (= 150M (:volume state2)))))) ;; Sum(100, 50)

  (testing "Deve fechar candle quando a hora vira"
    (let [ts-1500 (t/instant "2025-01-01T15:00:00Z")

          state-14h {:timestamp (t/instant "2025-01-01T14:00:00Z")
                     :open 100M :close 105M :high 110M :low 90M :volume 1000M :timeframe "1h"}

          new-candle {:timestamp ts-1500 :open 105M :close 106M :high 106M :low 105M :volume 10M}

          result (sut/process-tick state-14h new-candle "1h")]

      ;; Deve ter fechado o das 14h
      (is (some? (:closed result)))
      (is (= 105M (:close (:closed result))))
      (is (= "1h" (:timeframe (:closed result))))

      ;; O estado atual deve ser o inicio das 15h
      (is (= ts-1500 (:timestamp (:state result)))))))
