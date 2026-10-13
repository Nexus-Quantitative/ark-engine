(ns com.nexus-quant.ark-engine.orchestrator.backfill
  (:require [com.nexus-quant.ark-engine.connector.interface :as conn]
            [com.nexus-quant.ark-engine.domain-model.interface :as domain]
            [com.nexus-quant.ark-engine.temporal-db.interface :as db]
            [clojure.tools.logging :as log]))

(def BATCH-SIZE
  "Documentos por transacao XTDB. O custo do tx-log e' por transacao, nao por
   documento, entao lotes grandes sao ordens de grandeza mais baratos."
  500)

(defn- coerce-all
  "Coage todos os candles ANTES de agrupar.

  Isto e' essencial: `ingest-bars!` e' uma transacao unica, entao um unico
  documento invalido derrubaria o lote inteiro. Coagindo primeiro, cada candle
  ruim e' descartado de forma isolada — o mesmo comportamento de falha que o
  loop um-a-um tinha."
  [raw-candles]
  (reduce (fn [acc raw]
            (try
              (update acc :valid conj (domain/coerce-candle raw))
              (catch Exception e
                (log/error e "Failed to coerce candle" raw)
                (update acc :failed inc))))
          {:valid [] :failed 0}
          raw-candles))

(defn perform-backfill! [node connector symbol timeframe limit]
  (log/info "Starting backfill for" symbol timeframe "Limit:" limit)
  (try
    (let [raw-candles        (conn/fetch-history connector symbol timeframe limit)
          total              (count raw-candles)
          {:keys [valid failed]} (coerce-all raw-candles)
          n-valid            (count valid)]

      (log/info "Fetched" total "candles. Ingesting...")

      (when (pos? failed)
        (log/warn "Dropped" failed "invalid candles before writing"))

      (log/info "Writing" n-valid "candles in batches of" BATCH-SIZE)

      (doseq [[idx batch] (map-indexed vector (partition-all BATCH-SIZE valid))]
        (db/ingest-bars! node batch)
        (log/info "Imported" (min (* (inc idx) BATCH-SIZE) n-valid) "/" n-valid))

      ;; Flush antes de devolver: sem isto o store em disco fica dominado por
      ;; WAL nao compactado e o tamanho medido nao e' representativo.
      (db/sync! node)
      (log/info "Backfill complete for" symbol))

    (catch Exception e
      (log/error e "Backfill failed"))))
