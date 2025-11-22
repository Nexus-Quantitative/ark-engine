(ns com.nexus-quant.ark-engine.risk-guard.interface
  (:require [com.nexus-quant.ark-engine.risk-guard.core :as core]))

(defn validate-order!
  "Validate an order proposal against the System Constitution.
   
   Args:
     portfolio   (map): Current account state {:total-equity :high-water-mark :positions ...}
     order       (map): The intent {:symbol :side :quantity :price ...}
     market-data (map): Market snapshot {:bid :ask :spread-pct ...}
   
   Returns:
     :approved if the order passes all validations.
   
   Throws:
     clojure.lang.ExceptionInfo with structured data about the violation (ex-data)."
  [portfolio order market-data]
  (core/validate-order! portfolio order market-data))

(defn current-status
  "Return a map with current risk metrics (Drawdown, Leverage)."
  [portfolio]
  (core/calculate-health-metrics portfolio))

(defn system-constitution
  "Return the map of immutable rules (Risk Constants). Use this to display limits in the Dashboard or validate strategies."
  []
  (core/get-constitution))
