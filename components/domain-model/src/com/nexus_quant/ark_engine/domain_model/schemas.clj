(ns com.nexus-quant.ark-engine.domain-model.schemas
  (:require [malli.core :as m]
            [malli.transform :as mt]))

;; --- CUSTOM TYPE REGISTRY ---
(def registry
  (merge
   (m/default-schemas)
   {:decimal
    [:fn {:error/message "Must be a BigDecimal"
          :description "High-precision monetary value (prevents IEEE 754 errors)"
          :decode/json (fn [x]
                         (cond
                           (string? x) (bigdec x)
                           ;; REMOVED: (number? x) -> We do not accept Doubles anymore.
                           ;; This forces the Ingestor to parse JSON with useBigDecimals=true
                           :else x))}
     (fn [x] (instance? java.math.BigDecimal x))]

    :instant
    [:fn {:error/message "Must be java.time.Instant"
          :description "UTC Timestamp (ISO-8601)"
          :decode/json (fn [x]
                         (cond
                           (string? x) (java.time.Instant/parse x)
                           (number? x) (java.time.Instant/ofEpochMilli (long x))
                           :else x))}
     (fn [x] (instance? java.time.Instant x))]}))

;; --- COERCION TRANSFORMERS ---
(def json-transformer
  (mt/transformer
   mt/string-transformer
   mt/json-transformer))

;; --- 1. WIRE FORMATS (Input Layer) ---
;; Tolerant schemas for raw data ingestion

(def WireCandle
  [:map {:closed false}
   [:type      [:= "candle"]]
   [:symbol    :string]
   [:timeframe :string]
   [:open      :decimal]
   [:high      :decimal]
   [:low       :decimal]
   [:close     :decimal]
   [:volume    :decimal]
   [:timestamp :instant]])

(def WireTick
  [:map {:closed false}
   [:type      [:= "tick"]]
   [:symbol    :string]
   [:price     :decimal]
   [:volume    :decimal]
   [:timestamp :instant]])

;; --- 2. CORE ENTITIES (Domain Layer) ---
;; Strict schemas for internal logic

(def Candle
  [:map {:closed true}
   [:type      [:= :candle]]
   [:symbol    :string]
   [:timeframe [:enum "1m" "5m" "15m" "1h" "4h" "1d"]]
   ;; STRICT: OHLC is mandatory. No partial candles allowed in DB.
   [:open      :decimal]
   [:high      :decimal]
   [:low       :decimal]
   [:close     :decimal]
   [:volume    :decimal]
   [:timestamp :instant]])

(def Tick
  [:map {:closed true}
   [:type      [:= :tick]]
   [:symbol    :string]
   [:price     :decimal]
   [:volume    :decimal]
   [:timestamp :instant]])

(def StrategySignal
  [:map {:closed true}
   [:xt/id :uuid]
   [:doc-type [:= :strategy-signal]]
   [:strategy/id :string]
   [:strategy/version :string]
   [:signal/action [:enum :buy :sell :close :hold]]
   [:signal/confidence {:min 0.0 :max 1.0} :double]
   [:signal/context [:map [:price :decimal] [:indicators :map] [:infra/latency-ms :int]]]
   [:timestamp :instant]])



