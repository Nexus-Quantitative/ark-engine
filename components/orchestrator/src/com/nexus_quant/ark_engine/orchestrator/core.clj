(ns com.nexus-quant.ark-engine.orchestrator.core
  (:require [com.nexus-quant.ark-engine.temporal-db.interface :as db]
            [com.nexus-quant.ark-engine.strategy-engine.interface :as strat]
            [com.nexus-quant.ark-engine.risk-guard.interface :as risk]
            [clojure.tools.logging :as log]
            [clojure.core.async :as async :refer [go-loop timeout]]))

(def DEFAULT-OPTS
  {:symbol "BTC/USDT"
   :timeframe "1m"
   :strategy :trend-following
   :loop-interval-ms 60000 ;; 1 Minuto (Candle Close)
   :execution-mode :paper}) ;; :live ou :paper

(defn- fetch-context [node opts]
  (let [{:keys [symbol timeframe]} opts
        ;; 1. Busca histórico para indicadores (ex: 200 candles para EMA200)
        history (db/get-history node symbol timeframe 250)
        ;; 2. (Futuro) Buscar saldo real via Connector
        portfolio {:total-equity 10000M :positions {}}]
    {:history history
     :portfolio portfolio}))

(defn- execute-signal! [signal opts]
  (let [{:keys [execution-mode symbol]} opts
        action (:signal/action signal)]

    (if (= action :hold)
      (log/info "STRATEGY: HOLD | Context:" (:signal/context signal))

      ;; Se for BUY/SELL, passa pelo Risco
      (try
        ;; 1. Converter Sinal em "Intenção de Ordem"
        ;; (Simplificado para MVP - aqui entraria sizing logic)
        (let [order {:symbol symbol
                     :side action
                     :quantity 0.01M
                     :type :market
                     :client-oid (str (java.util.UUID/randomUUID))}

              ;; 2. RISK CHECK (A Constituição)
              risk-verdict (risk/validate-order! (:portfolio signal) order {})]

          (if (= risk-verdict :approved)
            (do
              (log/info "RISK: APPROVED. Executing:" action)
              (case execution-mode
                :live  (log/warn "LIVE EXECUTION NOT IMPLEMENTED YET")
                :paper (log/info "[PAPER] Order Sent:" order)))

            (log/warn "RISK: REJECTED.")))

        (catch Exception e
          (log/error "Orchestrator Error:" (.getMessage e)))))))

(defn run-cycle! [node opts]
  (log/info "--- Starting Cycle ---")
  (let [{:keys [history portfolio]} (fetch-context node opts)]
    (if (empty? history)
      (log/warn "No data found. Waiting for Ingestor...")

      ;; O CÉREBRO: Strategy Engine
      (let [signal (strat/compute-signal (:strategy opts) history portfolio)]
        (execute-signal! (assoc signal :portfolio portfolio) opts)))))

(defn start! [node opts]
  (let [final-opts (merge DEFAULT-OPTS opts)
        stop-ch (async/chan)]

    (log/info "Orchestrator Started. Mode:" (:execution-mode final-opts))

    (go-loop []
      (let [[_ port] (async/alts! [stop-ch (timeout (:loop-interval-ms final-opts))])]
        (if (= port stop-ch)
          (log/info "Orchestrator Stopped.")
          (do
            (try
              (run-cycle! node final-opts)
              (catch Exception e (log/error e "Cycle Crash")))
            (recur)))))

    (fn [] (async/close! stop-ch))))
