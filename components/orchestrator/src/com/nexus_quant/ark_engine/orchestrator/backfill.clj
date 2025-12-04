(ns com.nexus-quant.ark-engine.orchestrator.backfill
  (:require [com.nexus-quant.ark-engine.connector.interface :as conn]
            [com.nexus-quant.ark-engine.domain-model.interface :as domain]
            [com.nexus-quant.ark-engine.temporal-db.interface :as db]
            [clojure.tools.logging :as log]))

(defn perform-backfill! [node connector symbol timeframe limit]
  (log/info "Starting backfill for" symbol timeframe "Limit:" limit)
  (try
    (let [raw-candles (conn/fetch-history connector symbol timeframe limit)
          total (count raw-candles)]
      (log/info "Fetched" total "candles. Ingesting...")

      (doseq [[idx raw] (map-indexed vector raw-candles)]
        (try
          (let [clean (domain/coerce-candle raw)]
            (db/ingest-bar! node clean))
          (catch Exception e
            (log/error e "Failed to ingest candle" raw)))

        (when (zero? (mod (inc idx) 100))
          (log/info "Imported" (inc idx) "/" total "candles...")))

      (log/info "Backfill complete for" symbol))
    (catch Exception e
      (log/error e "Backfill failed"))))