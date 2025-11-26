(ns com.nexus-quant.ark-engine.domain-model.interface
  (:require [com.nexus-quant.ark-engine.domain-model.schemas :as schemas]
            [malli.core :as m]
            [malli.error :as me]))

;; Expose Schemas for external use
(def Candle schemas/Candle)
(def WireCandle schemas/WireCandle)
(def StrategySignal schemas/StrategySignal)

(defn validate!
  "Validates data against a schema using the custom registry.
   Throws an ExceptionInfo with detailed human-readable errors if invalid.
   
   Returns: true if valid."
  [schema data]
  (if (m/validate schema data {:registry schemas/registry})
    true
    (throw (ex-info "Domain Validation Failed"
                    {:type :schema-violation
                     :errors (me/humanize (m/explain schema data {:registry schemas/registry}))
                     :schema schema
                     :data data}))))

(defn coerce-candle
  "Transform raw dirty payloads (Redis/JSON) into strict Domain Candles.
   
   Process:
   1. Decodes using WireCandle (String -> BigDecimal/Instant).
   2. Enriches with domain tags (:type :candle).
   3. Validates against strict Candle schema.
   
   Throws: ExceptionInfo if coercion fails or result is invalid."
  [raw-data]
  (let [coerced (m/decode (m/schema schemas/WireCandle {:registry schemas/registry})
                          raw-data
                          schemas/json-transformer)
        ;; Enrich with discriminator
        enriched (assoc coerced :type :candle)]

    ;; Final strict validation
    (if (m/validate schemas/Candle enriched {:registry schemas/registry})
      enriched
      (throw (ex-info "Candle Coercion Failed"
                      {:type :schema-violation
                       :errors (me/humanize (m/explain schemas/Candle enriched {:registry schemas/registry}))
                       :raw raw-data
                       :coerced enriched})))))
