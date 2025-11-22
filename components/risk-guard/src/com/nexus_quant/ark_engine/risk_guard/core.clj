(ns com.nexus-quant.ark-engine.risk-guard.core
  (:require [clojure.string :as str])
  (:import [java.time Instant Duration]))

;; --- CONSTITUTION CONSTANTS (HARD STOPS) ---
;; All monetary values are defined as BigDecimal (M suffix) for precision.
(def ^:const MAX-MONTHLY-DRAWDOWN 0.15M)
(def ^:const MAX-LEVERAGE 3.0M)
(def ^:const MAX-SINGLE-POS-SIZE 0.20M)
(def ^:const MAX-SPREAD-PCT 0.0015M)
(def ^:const MAX-DATA-STALENESS-MS 2000) ;; 2 seconds of tolerance

(def SAFE-HAVEN-ASSETS #{"BTC/USDT" "ETH/USDT" "USDT" "USDC"})

;; --- POLICY EXPOSURE (DRY SOLUTION) ---
(defn get-constitution
  "Returns the immutable rules as a data structure."
  []
  {:max-monthly-drawdown MAX-MONTHLY-DRAWDOWN
   :max-leverage MAX-LEVERAGE
   :max-single-pos-size MAX-SINGLE-POS-SIZE
   :max-spread-pct MAX-SPREAD-PCT
   :safe-haven-assets SAFE-HAVEN-ASSETS})

;; --- HELPER FUNCTIONS ---

(defn- to-bigdec [n]
  (if (nil? n) 0.0M (bigdec n)))

(defn- stale-data? [timestamp]
  (let [now (System/currentTimeMillis)
        data-time (inst-ms timestamp)]
    (> (- now data-time) MAX-DATA-STALENESS-MS)))

(defn- calc-drawdown [total-equity high-water-mark]
  (let [eq (to-bigdec total-equity)
        hwm (to-bigdec high-water-mark)]
    (if (or (zero? hwm) (>= eq hwm))
      0.0M
      ;; (HWM - Equity) / HWM
      (with-precision 10 :rounding HALF_UP
                      (/ (- hwm eq) hwm)))))

(defn- calc-projected-leverage [portfolio order-notional]
  (let [current-exposure (to-bigdec (:total-exposure portfolio))
        equity (to-bigdec (:total-equity portfolio))]
    (if (zero? equity)
      999.0M ;; Infinite/Error
      (with-precision 10 :rounding HALF_UP
                      (/ (+ current-exposure order-notional) equity)))))

;; --- THE GATEKEEPER ---

(defn validate-order!
  "Validates a proposed order against the System Constitution.
   Throws ex-info with structured data on rejection."
  [portfolio order market-data]

  ;; 1. DATA INTEGRITY & FRESHNESS CHECK
  (when (or (nil? portfolio) (nil? (:timestamp portfolio)))
    (throw (ex-info "RISK ERROR: Portfolio state corrupted/incomplete." {:type :integrity})))

  (when (stale-data? (:timestamp portfolio))
    (throw (ex-info "RISK REJECTION: Portfolio state is stale (Lag)."
                    {:type :latency
                     :data-age-ms (- (System/currentTimeMillis) (inst-ms (:timestamp portfolio)))})))

  ;; 2. BYPASS FOR RISK REDUCTION (Artigo VI, 6.1)
  ;; Orders tagged :reduce-only? are allowed to bypass leverage/drawdown checks.
  (if (:reduce-only? order)
    :approved

    ;; Proceed with full analysis for new/increase orders:
    (let [equity        (to-bigdec (:total-equity portfolio))
          hwm           (to-bigdec (:high-water-mark portfolio))
          drawdown      (calc-drawdown equity hwm)
          price         (to-bigdec (:price order))
          qty           (to-bigdec (:quantity order))
          notional      (* price qty)
          proj-leverage (calc-projected-leverage portfolio notional)
          spread        (to-bigdec (:spread-pct market-data))]

      ;; 3. VERIFICATION OF RUIN (Artigo I, 1.1)
      (when (> drawdown MAX-MONTHLY-DRAWDOWN)
        (throw (ex-info "RISK REJECTION: Global Drawdown Limit Hit."
                        {:type :hard-stop :drawdown drawdown :limit MAX-MONTHLY-DRAWDOWN})))

      ;; 4. VERIFICATION OF LEVERAGE (Artigo II, 2.1)
      (when (> proj-leverage MAX-LEVERAGE)
        (throw (ex-info "RISK REJECTION: Leverage Limit Exceeded."
                        {:type :leverage :projected proj-leverage :limit MAX-LEVERAGE})))

      ;; 5. VERIFICATION OF LIQUIDITY (Artigo IV, 4.1)
      (when (> spread MAX-SPREAD-PCT)
        (throw (ex-info "RISK REJECTION: Spread too high."
                        {:type :liquidity :spread spread :limit MAX-SPREAD-PCT})))

      :approved)))

;; --- HEALTH METRICS (The Dashboard Data) ---

(defn calculate-health-metrics
  "Calculates and sanitizes the primary health metrics for the TUI dashboard."
  [{:keys [total-equity high-water-mark current-leverage total-exposure]}]
  (let [equity (to-bigdec total-equity)
        hwm (to-bigdec high-water-mark)
        current-leverage (to-bigdec current-leverage)
        drawdown (calc-drawdown equity hwm)]

    {:drawdown drawdown
     :leverage current-leverage
     :exposure (to-bigdec total-exposure)
     :drawdown-limit-pct (with-precision 2 (* (/ drawdown MAX-MONTHLY-DRAWDOWN) 100M))
     :risk-status (cond
                    ;; Statuses for the TUI:
                    (> drawdown (/ MAX-MONTHLY-DRAWDOWN 2)) :high-risk-drawdown
                    (> current-leverage 2.0M) :high-risk-leverage
                    :else :optimal)}))