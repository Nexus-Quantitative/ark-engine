(ns com.nexus-quant.ark-engine.ingestor.core
  (:require [taoensso.carmine :as car]
            [com.nexus-quant.ark-engine.temporal-db.interface :as db]
            [com.nexus-quant.ark-engine.domain-model.interface :as domain]
            [com.nexus-quant.ark-engine.candle-aggregator.interface :as agg] ;; NEW
            [clojure.tools.logging :as log]
            [cheshire.core :as json] ;; NEW
            [clojure.walk :refer [keywordize-keys]]
            [clojure.core.async :as async :refer [go-loop <! chan close!]]))

;; ... [Configuration & Redis Helpers remain same] ...
(def DEFAULT-CONFIG
  {:redis-conn {:pool {} :spec {:host "localhost" :port 6379}}
   :stream-key "market-events"
   :group-name "ark-ingestor-group"
   :dlq-key "market-events-dlq"})

(defn- run-cmd [conn cmd-fn & args]
  (car/wcar conn (apply cmd-fn args)))

(defn- ensure-group! [conn stream group]
  (try
    (run-cmd conn car/xgroup "CREATE" stream group "$" "MKSTREAM")
    (log/info "Group created:" group "on" stream)
    (catch Exception e
      (let [msg (.getMessage e)]
        (if (and msg (.contains msg "BUSYGROUP"))
          (log/info "Group exists (BUSYGROUP):" group)
          (log/error e "Failed to create group"))))))

;; --- SAFE PARSING (The Firewall) ---

(defn- safe-parse
  "Deserializes Redis fields. 
   Uses Cheshire with strict BigDecimal support to prevent float errors."
  [raw-fields]
  (try
    (let [base-map (keywordize-keys (apply hash-map raw-fields))
          data-str (:data base-map)]

      (if (string? data-str)
        ;; Parse with bigdecimals=true and keyword-fn to keywordize keys only
        {:status :ok :payload (assoc base-map :data (json/parse-string data-str keyword true))}
        {:status :ok :payload base-map}))

    (catch Exception e
      {:status :error :reason :malformed-json :ex e :raw raw-fields})))

(defn- send-to-dlq! [config id raw-fields reason]
  (let [{:keys [redis-conn dlq-key stream-key group-name]} config]
    (log/warn "☠️ Moving to DLQ:" dlq-key reason)
    (try
      (car/wcar redis-conn
                (apply car/xadd [dlq-key "*" "original-id" id "reason" (str reason) "raw" (pr-str raw-fields)])
                (apply car/xack [stream-key group-name id]))
      (catch Exception e (log/error e "DLQ Failed!")))))

;; --- AGGREGATION LOGIC ---

(def TARGET-TIMEFRAMES ["1h" "4h" "1d"])

(defn- handle-aggregation!
  "Atualiza o estado de agregação e persiste candles fechados."
  [xtdb-node state-atom new-candle]
  (let [symbol (:symbol new-candle)]
    (doseq [tf TARGET-TIMEFRAMES]
      ;; 1. Recupera estado atual para este Símbolo + Timeframe
      (let [current-state (get-in @state-atom [symbol tf])

            ;; 2. Processa matematicamente
            result (agg/aggregate current-state new-candle tf)]

        ;; 3. Atualiza memória (Swap)
        (swap! state-atom assoc-in [symbol tf] (:state result))

        ;; 4. Se fechou, persiste no Banco
        (when-let [closed (:closed result)]
          (log/info "📉 Candle Closed:" symbol tf (:close closed))
          (db/ingest-bar! xtdb-node closed))))))

;; --- PROCESSING ROUTER ---

(defn- process-valid-event! [config xtdb-node state-atom id event] ;; NOVO ARGUMENTO: state-atom
  (let [{:keys [redis-conn stream-key group-name]} config
        raw-data (:data event)
        type (keyword (:type raw-data))]

    (try
      (case type
        :candle
        (let [clean-candle (domain/coerce-candle raw-data)]
          ;; A. Persiste o 1m (Base)
          (db/ingest-bar! xtdb-node clean-candle)
          ;; B. Alimenta os Agregadores (1h, 4h...)
          (handle-aggregation! xtdb-node state-atom clean-candle))

        :tick
        (let [clean-tick (domain/coerce-tick raw-data)]
          (log/trace "Tick received:" (:price clean-tick)))

        :signal
        (let [ts (or (:timestamp raw-data) (:ts raw-data) (java.time.Instant/now))]
          (db/ingest-signal! xtdb-node raw-data ts))

        (log/debug "Ignored type:" type))

      (run-cmd redis-conn car/xack stream-key group-name id)

      (catch clojure.lang.ExceptionInfo e
        (if (= :schema-violation (:type (ex-data e)))
          (send-to-dlq! config id (:data event) :schema-violation)
          (throw e)))
      (catch Exception e
        (log/error e "Transient failure")
        (throw e)))))

(defn start!
  ([xtdb-node] (start! xtdb-node DEFAULT-CONFIG))
  ([xtdb-node config]
   (let [final-config (merge DEFAULT-CONFIG config)
         {:keys [redis-conn stream-key group-name]} final-config
         consumer-name (str "worker-" (java.util.UUID/randomUUID))
         stop-ch (chan)

         ;; ESTADO MUTÁVEL LOCAL (A Memória do Worker)
         ;; Formato: { "BTC/USDT" { "1h" {...}, "4h" {...} } }
         aggregation-state (atom {})]

     (ensure-group! redis-conn stream-key group-name)
     (log/info "Loop started for" consumer-name)

     (go-loop []
       (let [[_ port] (async/alts! [stop-ch] :default :continue)]
         (if (= port stop-ch)
           (log/info "Worker stopped.")
           (do
             (try
               (let [messages (run-cmd redis-conn car/xreadgroup
                                       "GROUP" group-name consumer-name
                                       "BLOCK" "2000" "COUNT" "10"
                                       "STREAMS" stream-key ">")]
                 (when (seq messages)
                   (let [[_ stream-items] (first messages)]
                     (doseq [[id fields] stream-items]
                       (let [{:keys [status payload reason]} (safe-parse fields)]
                         (if (= status :ok)
                           (process-valid-event! final-config xtdb-node aggregation-state id payload)
                           (send-to-dlq! final-config id fields reason)))))))
               (catch Exception e (log/error "Loop Error:" (.getMessage e))))
             (<! (async/timeout 10))
             (recur)))))
     (fn stop-fn [] (close! stop-ch)))))