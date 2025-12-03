(ns com.nexus-quant.ark-engine.strategy-engine.core
  (:require [com.nexus-quant.ark-engine.strategy-engine.strategies.trend-following :as trend]))

(defn compute-signal
  "Routes to the correct strategy and appends decision timestamp."
  [strategy-name candles portfolio]
  (let [signal (case strategy-name
                 :trend-following (trend/analyze candles portfolio)
                 ;; Default Fallback
                 {:signal/action :hold
                  :strategy/id "unknown"
                  :strategy/version "0.0.0"
                  :signal/confidence 0.0
                  :signal/context {}})]
    ;; Enriches with decision time (Now)
    (assoc signal :timestamp (java.time.Instant/now))))
