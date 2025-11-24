(ns com.nexus-quant.ark-engine.risk-guard.generative-test
  (:require [clojure.test :as t]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [com.nexus-quant.ark-engine.risk-guard.core :as sut]))

;; --- 1. ROBUST DATA GENERATORS (THE "MONEY PATTERN") ---
;; We strictly avoid generating Doubles to prevent IEEE 754 artifacts (NaN, Infinity).
;; Instead, we generate Large Integers (cents/pips) and divide by a scaling factor
;; to produce mathematically clean BigDecimals.

(def gen-safe-money
  "Generates positive BigDecimals derived from integers.
   Scale: 2 (cents). Range: 0.01 to 10,000,000.00"
  (gen/fmap (fn [n] (with-precision 10 (/ (bigdec n) 100.0M)))
            (gen/large-integer* {:min 1 :max 1000000000})))

(def gen-safe-spread
  "Generates realistic market spreads.
   Scale: 4 (pips). Range: 0.0001 (1 bp) to 0.1 (10%)."
  (gen/fmap (fn [n] (with-precision 10 (/ (bigdec n) 10000.0M)))
            (gen/large-integer* {:min 1 :max 1000})))

(def gen-portfolio
  "Generates a coherent portfolio state.
   Invariant: High Water Mark (HWM) is always >= Equity."
  (gen/let [equity gen-safe-money
            ;; HWM is Equity + a positive delta
            hwm-delta (gen/fmap (fn [n] (with-precision 10 (/ (bigdec n) 100.0M)))
                                (gen/large-integer* {:min 0 :max 1000000}))
            exposure  gen-safe-money]
    {:total-equity equity
     :high-water-mark (+ equity hwm-delta)
     :total-exposure exposure
     :timestamp (java.time.Instant/now)}))

(def gen-order
  "Generates execution orders using safe monetary values."
  (gen/let [price gen-safe-money
            qty   gen-safe-money
            reduce? gen/boolean]
    {:symbol "BTC/USDT"
     :price price
     :quantity qty
     :reduce-only? reduce?}))

(def gen-market-data
  "Generates market snapshots using the safe spread generator."
  (gen/let [spread gen-safe-spread]
    {:spread-pct spread}))

;; --- 2. INVARIANT PROPERTIES (THE CONSTITUTION LAWS) ---

(defspec prop-drawdown-hard-stop 1000
  (prop/for-all [portfolio gen-portfolio
                 order     gen-order
                 market    gen-market-data]
                (let [equity (:total-equity portfolio)
                      hwm    (:high-water-mark portfolio)

                      ;; ORACLE CALCULATION
                      ;; We replicate the logic with strict precision context to avoid ArithmeticException
                      drawdown (try
                                 (if (pos? hwm)
                                   (with-precision 10 :rounding HALF_UP (/ (- hwm equity) hwm))
                                   0.0M)
                                 (catch ArithmeticException _ 0.0M))]

                  (if (and (> drawdown 0.15M)          ;; CONDITION: Ruin threshold breached
                           (not (:reduce-only? order))) ;; CONDITION: Not a risk-reduction order

                    ;; EXPECTATION: System MUST Reject
                    (try
                      (sut/validate-order! portfolio order market)
                      false ;; Fail: Order was wrongly approved
                      (catch clojure.lang.ExceptionInfo e
                        ;; Pass: Correctly rejected with :hard-stop
                        (= :hard-stop (:type (ex-data e)))))

                    ;; EXPECTATION: If not in drawdown, this specific property holds true (vacuously).
                    true))))

(defspec prop-leverage-limit 1000
  (prop/for-all [portfolio gen-portfolio
                 order     gen-order
                 market    gen-market-data]
                (let [equity   (:total-equity portfolio)
                      exposure (:total-exposure portfolio)
                      notional (* (:price order) (:quantity order))

                      ;; ORACLE CALCULATION
                      new-lev (try
                                (with-precision 10 :rounding HALF_UP
                                                (/ (+ exposure notional) equity))
                                (catch ArithmeticException _ 999.0M))] ;; Treat div/0 as infinite leverage

                  (if (and (> new-lev 3.0M)            ;; CONDITION: Excessive Leverage
                           (not (:reduce-only? order)))

                    ;; EXPECTATION: System MUST Reject
                    (try
                      (sut/validate-order! portfolio order market)
                      false ;; Fail: Dangerous leverage accepted
                      (catch clojure.lang.ExceptionInfo e
                        (let [type (:type (ex-data e))]
                          ;; Pass: Rejected by Leverage OR Drawdown (Safety Hierarchy)
                          ;; It's acceptable to reject by Drawdown even if we are testing Leverage.
                          (or (= type :leverage)
                              (= type :hard-stop)))))

                    ;; EXPECTATION: Within limits
                    true))))

(defspec prop-bypass-reduce-only 500
  (prop/for-all [portfolio gen-portfolio
                 order     (gen/fmap #(assoc % :reduce-only? true) gen-order) ;; Force reduce-only
                 market    gen-market-data]
                ;; INVARIANT: Risk reduction orders must ALWAYS be approved,
                ;; regardless of Drawdown or Leverage state.
                (= :approved (sut/validate-order! portfolio order market))))