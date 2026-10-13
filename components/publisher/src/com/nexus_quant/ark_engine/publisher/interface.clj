(ns com.nexus-quant.ark-engine.publisher.interface
  (:require [com.nexus-quant.ark-engine.publisher.core :as core]))

(defn start!
  "Conecta no feed do exchange e publica candles no Redis Stream.

  Args:
    :connector - instancia que satisfaz ExchangeConnector (conector do exchange)
    :topics    - vetor de {:type :candle :symbol \"BTC/USDT\"}
    :config    - overrides opcionais (:redis-conn :stream-key :max-stream-len)

  Returns:
    {:stop-fn f  :stats atom  :input-ch channel}"
  [opts]
  (core/start! opts))
