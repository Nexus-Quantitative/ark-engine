(ns com.nexus-quant.ark-engine.domain-model.interface-test
  (:require [clojure.test :refer [deftest testing is]]
            [com.nexus-quant.ark-engine.domain-model.interface :as sut])
  (:import [java.time Instant]))

(deftest coerce-candle-test
  ;; CENÁRIO 1: Sucesso (Happy Path)
  (testing "Should coerce JSON strings into Domain Types (BigDecimal/Instant)"
    (let [raw-input {:symbol "BTC/USDT"
                     :timeframe "1m"
                     :close "98000.50"  ;; String (Simulando Redis/JSON)
                     :volume 150.5       ;; Number (Double)
                     :timestamp "2025-01-01T12:00:00Z"}

          coerced (sut/coerce-candle raw-input)]

      (is (instance? java.math.BigDecimal (:close coerced)) "Price must be coerced to BigDecimal")
      (is (instance? java.time.Instant (:timestamp coerced)) "Timestamp must be coerced to Instant")
      (is (= 98000.50M (:close coerced)) "Numeric value must be preserved exactly")
      (is (= :candle (:type coerced)) "Discriminator type must be injected")))

  ;; CENÁRIO 2: Falha por Dados Faltantes
  (testing "Should fail when mandatory fields are missing"
    (let [bad-input {:symbol "BTC/USDT"
                     ;; Missing price (:close) and volume (:volume)
                     :timestamp "2025-01-01T10:00:00Z"}]

      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Candle Coercion Failed"
                            (sut/coerce-candle bad-input)))))

  ;; CENÁRIO 3: Falha por Campos Proibidos (Schema Fechado)
  (testing "Should reject unknown fields (Strict Domain Boundary)"
    (let [dirty-input {:symbol "BTC/USDT"
                       :timeframe "1m"
                       :close 100
                       :volume 10
                       :timestamp "2025-01-01T00:00:00Z"
                       :hacker_payload "drop table users"}] ;; Campo não permitido

      (is (thrown? clojure.lang.ExceptionInfo
                   (sut/coerce-candle dirty-input))))))



