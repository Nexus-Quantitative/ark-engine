(ns com.nexus-quant.ark-engine.strategy-engine.strategies.trend-following
  (:require [com.nexus-quant.ark-engine.strategy-engine.indicators :as ind]))

(def STRATEGY-ID "trend-following-v1")
(def MIN-CANDLES 50) ;; Needs history to calculate EMA

(defn analyze
  "Analyzes market and returns a SIGNAL (Intention), not an Order."
  [candles _portfolio]
  (let [cnt (count candles)]
    (if (< cnt MIN-CANDLES)
      ;; 1. Insufficient Data -> Hold
      {:signal/action :hold
       :signal/confidence 0.0
       :strategy/id STRATEGY-ID
       :strategy/version "1.0.0"
       :signal/context {:reason "Insufficient Data" :points cnt}}

      ;; 2. Mathematical Calculation
      (let [last-c (:close (last candles))
            ema-fast (ind/ema candles 9)
            ema-slow (ind/ema candles 21)

            ;; Context Snapshot (For Audit/Telegram)
            context {:price last-c
                     :indicators {:ema-9 (double ema-fast)
                                  :ema-21 (double ema-slow)}}]

        (cond
          ;; BUY SIGNAL: Golden Cross
          (> ema-fast ema-slow)
          {:signal/action :buy
           :signal/confidence 0.8
           :strategy/id STRATEGY-ID
           :strategy/version "1.0.0"
           :signal/context context}

          ;; SELL SIGNAL: Death Cross
          (< ema-fast ema-slow)
          {:signal/action :sell
           :signal/confidence 0.6
           :strategy/id STRATEGY-ID
           :strategy/version "1.0.0"
           :signal/context context}

          :else
          {:signal/action :hold
           :signal/confidence 0.0
           :strategy/id STRATEGY-ID
           :strategy/version "1.0.0"
           :signal/context context})))))
