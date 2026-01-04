(ns com.nexus-quant.ark-engine.connector.interface
  (:require [com.nexus-quant.ark-engine.connector.bitget :as bitget]
            [com.nexus-quant.ark-engine.connector.protocol :as p]))

(defn initialize! [this config]
  (p/initialize! this config))

(defn subscribe! [this topics output-channel]
  (p/subscribe! this topics output-channel))

(defn submit-order! [this order-params]
  (p/submit-order! this order-params))

(defn cancel-order! [this order-id symbol]
  (p/cancel-order! this order-id symbol))

(defn get-portfolio-state [this]
  (p/get-portfolio-state this))

(defn disconnect! [this]
  (p/disconnect! this))

(defn fetch-history [this symbol timeframe limit]
  (p/fetch-history this symbol timeframe limit))

(defn create-bitget-connector []
  (bitget/create))
