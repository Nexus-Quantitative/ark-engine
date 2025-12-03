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
        (let [price (+ 98000.0 (rand 50.0))
              result (a/put! output-channel
                             {:type :candle
                              :data {:type "candle"
                                     :symbol "BTC/USDT"
                                     :timeframe "1m"
                                     :open (str price)
                                     :high (str (+ price 10))
                                     :low (str (- price 10))
                                     :close (str price)
                                     :volume (str (rand 100))
                                     :timestamp (java.time.Instant/now)}})]

          (if result
            (do
              ;; Simulate Random Balance Push
              (when (< (rand) 0.05)
                (a/put! output-channel
                        {:type :account-update
                         :total-equity (bigdec (+ 10000.0 (rand 500.0)))}))
              (Thread/sleep 1000)
              (recur))
            (log/info "STUB: Channel closed, stopping loop.")))))
    this)

  (submit-order! [_ order]
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
          {:status :rejected
           :reason :engine-overload}

          ;; Success (Ack)
          {:status       :filled
           :client-oid   (:client-oid order) ;; Echo back for confirmation
           :exchange-oid (str "stub-ex-" (java.util.UUID/randomUUID))
           :avg-price    (:price order)
           :filled-qty   (:quantity order)}))))

  (cancel-order! [_ id _sym]
    (log/info "STUB: Cancelled" id)
    true)

  (get-portfolio-state [_]
    {:total-equity 10000.0M
     :positions {}})

  (disconnect! [_]
    (log/info "STUB: Disconnected.")))
(defn create []
  (->StubExchange (atom {})))
