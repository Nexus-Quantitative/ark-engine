(ns com.nexus-quant.ark-engine.candle-aggregator.interface
  (:require [com.nexus-quant.ark-engine.candle-aggregator.core :as core]))

(defn aggregate
  "Processa um novo candle e retorna o próximo estado e eventuais candles fechados.
   Uso: (aggregate state new-candle '1h')"
  [current-state new-candle target-timeframe]
  (core/process-tick current-state new-candle target-timeframe))
