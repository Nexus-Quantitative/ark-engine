(ns com.nexus-quant.ark-engine.strategy-engine.interface
  (:require [com.nexus-quant.ark-engine.strategy-engine.core :as core]))

(defn compute-signal [strategy-name candles portfolio]
  (core/compute-signal strategy-name candles portfolio))
