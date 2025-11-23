(ns com.nexus-quant.ark-engine.connector.stub
  (:require [com.nexus-quant.ark-engine.connector.interface :as i]
            [clojure.core.async :as a]
            [clojure.tools.logging :as log]))

(defrecord StubExchange [state-atom]
  i/ExchangeConnector

  (initialize! [this _]
    (log/info "STUB: Initialized in Simulation Mode.")
    this)

  (subscribe! [this topics output-channel]
    (log/info "STUB: Subscribing to" topics)
    (future
      (loop []
        (when-not (a/closed? output-channel)
          ;; Simulate Ticker
          (a/put! output-channel
                  {:type :tick
                   :symbol "BTC/USDT"
                   :price (bigdec (+ 98000.0 (rand 50.0)))
                   :ts (System/currentTimeMillis)})

          ;; Simulate Random Balance Push (Race Condition Test)
          (when (< (rand) 0.05) ;; 5% chance
            (a/put! output-channel
                    {:type :account-update
                     :total-equity (bigdec (+ 10000.0 (rand 500.0)))}))

          (Thread/sleep 100)
          (recur))))
    this)

  (submit-order! [this order]
    (if-not (:client-oid order)
      ;; Simulating rejection due to lack of Idempotency
      (future {:status :rejected
               :reason :missing-client-oid
               :msg "FATAL: Engineering failure. No Client OID provided."})

      (future
        ;; Simulate network latency
        (Thread/sleep 50)

        ;; Simulate random Exchange failure (Chaos Monkey)
        (if (< (rand) 0.01)
          {:status :rejected :reason :engine-overload}

          ;; Success (Ack)
          {:status :filled
           :client-oid (:client-oid order) ;; Echo back for confirmation
           :exchange-oid (str "stub-ex-" (java.util.UUID/randomUUID))
           :avg-price (:price order)
           :filled-qty (:quantity order)}))))

  (cancel-order! [this id sym]
    (log/info "STUB: Cancelled" id)
    true)

  (get-portfolio-state [this]
    {:total-equity 10000.0M
     :positions {}})

  (disconnect! [this]
    (log/info "STUB: Disconnected.")))

(defn create []
  (->StubExchange (atom {})))