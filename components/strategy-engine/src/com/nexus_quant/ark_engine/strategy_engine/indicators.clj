(ns com.nexus-quant.ark-engine.strategy-engine.indicators
  (:require [tick.core :as t])
  (:import [org.ta4j.core BaseBarSeries BaseBar]
           [org.ta4j.core.indicators.helpers ClosePriceIndicator]
           [org.ta4j.core.indicators EMAIndicator RSIIndicator MACDIndicator]
           [org.ta4j.core.num DecimalNum]
           [java.time ZoneId]))

;; --- CONVERTERS (Clojure -> TA4J) ---

(defn- ->zoned-date-time [instant]
  (t/in instant (ZoneId/of "UTC")))

(defn- candle->bar [c]
  ;; Maps Malli keys (:close, :open) to TA4J Bar
  (BaseBar. (-> (:timestamp c) ->zoned-date-time)
            (double (:open c))
            (double (:high c))
            (double (:low c))
            (double (:close c))
            (double (:volume c))))

(defn- build-series [candles]
  (let [series (BaseBarSeries. "ark-series")]
    (doseq [c candles]
      (.addBar series (candle->bar c)))
    series))

(defn- get-last-value [indicator series]
  (let [last-idx (dec (.getBarCount series))]
    ;; Extracts calculated value and converts to safe BigDecimal
    (-> (.getValue indicator last-idx)
        (.getDelegate)
        (bigdec))))

;; --- PUBLIC INDICATORS API (Pure Functions) ---

(defn ema
  "Calculates Exponential Moving Average. Returns BigDecimal."
  [candles period]
  (let [series (build-series candles)
        close-price (ClosePriceIndicator. series)
        ema-ind (EMAIndicator. close-price period)]
    (get-last-value ema-ind series)))

(defn rsi
  "Calculates RSI (0-100)."
  [candles period]
  (let [series (build-series candles)
        close-price (ClosePriceIndicator. series)
        rsi-ind (RSIIndicator. close-price period)]
    (get-last-value rsi-ind series)))

(defn macd
  "Calculates MACD (12, 26, 9). Returns {:macd :signal :hist}"
  [candles]
  (let [series (build-series candles)
        close-price (ClosePriceIndicator. series)
        macd-ind (MACDIndicator. close-price 12 26)
        ema-signal (EMAIndicator. macd-ind 9)
        last-idx (dec (.getBarCount series))

        macd-val (-> (.getValue macd-ind last-idx) (.getDelegate) bigdec)
        sig-val  (-> (.getValue ema-signal last-idx) (.getDelegate) bigdec)]

    {:macd macd-val
     :signal sig-val
     :hist (- macd-val sig-val)}))
