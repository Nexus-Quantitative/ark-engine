(ns com.nexus-quant.ark-engine.connector.core
  (:require [com.nexus-quant.ark-engine.connector.stub :as stub]
            [com.nexus-quant.ark-engine.connector.bitget :as bitget]))

(defn create-connector [config]
  (case (:exchange-driver config)
    :bitget (bitget/create)
    (stub/create)))