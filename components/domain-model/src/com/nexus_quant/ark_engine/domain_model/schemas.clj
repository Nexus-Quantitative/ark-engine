(ns com.nexus-quant.ark-engine.domain-model.schemas
  (:require [malli.core :as m]
            [malli.transform :as mt]))

;; --- CUSTOM TYPE REGISTRY ---
;; Extends Malli to support financial primitives not present in standard JSON.

(def registry
  (merge
   (m/default-schemas)
   {:decimal
    [:fn {:error/message "Must be a BigDecimal"
          :description "High-precision monetary value (prevents IEEE 754 errors)"
          :decode/json (fn [x]
                         (cond
                           (string? x) (bigdec x)
                           (number? x) (bigdec x) ;; Handles Doubles/Floats -> BigDecimal
                           :else x))}
     (fn [x] (instance? java.math.BigDecimal x))]

    :instant
    [:fn {:error/message "Must be java.time.Instant"
          :description "UTC Timestamp (ISO-8601)"
          :decode/json (fn [x]
                         (cond
                           ;; Parses "2023-01-01T00:00:00Z"
                           (string? x) (java.time.Instant/parse x)
                           ;; Parses Unix Epoch Millis (Long)
                           (number? x) (java.time.Instant/ofEpochMilli (long x))
                           :else x))}
     (fn [x] (instance? java.time.Instant x))]}))

;; --- COERCION TRANSFORMERS ---
;; Defines rules to transform raw inputs (JSON/Redis Strings) into Domain Types.

(def json-transformer
  (mt/transformer
   mt/string-transformer ;; Coerces "10" -> 10 (integers)
   mt/json-transformer))   ;; Coerces keys "foo" -> :foo

;; --- 1. WIRE FORMATS (Input Layer) ---
;; Tolerant schemas used by the Ingestor to read from external APIs.
;; :closed false means we ignore extra garbage fields from the exchange.

(def WireCandle
  [:map {:closed false}
   [:symbol :string]
   [:timeframe :string]    ;; Accepts raw strings like "1m", validated later
   [:close     :decimal]   ;; Coerces "98000.50" -> 98000.50M
   [:volume    :decimal]
   [:timestamp :instant]])

;; --- 2. CORE ENTITIES (Domain Layer) ---
;; Strict schemas used by XTDB and Risk Guard.
;; :closed true means any extra field is considered data pollution (Bug).

(def Candle
  [:map {:closed true}
   [:type      [:= :candle]] ;; Discriminator field
   [:symbol    :string]
   [:timeframe [:enum "1m" "5m" "15m" "1h" "4h" "1d"]]
   [:open      {:optional true} :decimal] ;; OHLC might be partial in streams
   [:high      {:optional true} :decimal]
   [:low       {:optional true} :decimal]
   [:close     :decimal]
   [:volume    :decimal]
   [:timestamp :instant]])

(def ContextMap
  "Snapshot of the market state during a decision."
  [:map
   [:price :decimal]
   [:indicators [:map {:closed false} ;; Open to allow different strategy indicators
                 [:rsi {:optional true} :double]
                 [:bb-width {:optional true} :double]]]
   [:infra/latency-ms :int]])

(def StrategySignal
  "The audit document explaining WHY a trade happened."
  [:map {:closed true}
   [:xt/id :uuid]
   [:doc-type [:= :strategy-signal]]
   [:strategy/id :string]
   [:strategy/version :string] ;; CRITICAL for provenance
   [:signal/action [:enum :buy :sell :close :hold]]
   [:signal/confidence {:min 0.0 :max 1.0} :double]
   [:signal/context ContextMap]
   [:timestamp :instant]])



