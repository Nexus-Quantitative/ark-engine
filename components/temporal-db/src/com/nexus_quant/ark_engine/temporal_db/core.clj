(ns com.nexus-quant.ark-engine.temporal-db.core
  (:require [xtdb.api :as xt]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time Instant ZoneId]
           [java.time.format DateTimeFormatter]))

;; --- INFRASTRUCTURE CONFIGURATION ---

(defn- get-default-rocksdb-dir []
  (or (System/getenv "XTDB_DATA_DIR") "xtdb-data"))

(defn- rocksdb-topology [db-dir]
  {:xtdb/index-store    {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store :db-dir (io/file db-dir "indexes")}}
   :xtdb/document-store {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store :db-dir (io/file db-dir "documents")}}
   :xtdb/tx-log         {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store :db-dir (io/file db-dir "tx-log")}}})

(defn- memory-topology []
  ;; Light topology for tests. Everything in RAM.
  {:xtdb/index-store    {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}
   :xtdb/document-store {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}
   :xtdb/tx-log         {:kv-store {:xtdb/module 'xtdb.mem-kv/->kv-store}}})

(defn start-node!
  "Starts the XTDB node.
   Config keys:
     :store  -> :rocksdb (default) or :memory
     :db-dir -> Path (only for rocksdb)"
  ([] (start-node! {}))
  ([config]
   (let [store-type (get config :store :rocksdb)
         topology   (if (= store-type :memory)
                      (do (println "[XTDB] Starting In-Memory Node (Test Mode)")
                          (memory-topology))
                      (let [dir (or (:db-dir config) (get-default-rocksdb-dir))]
                        (println "[XTDB] Starting RocksDB at:" dir)
                        (rocksdb-topology dir)))]

     (xt/start-node topology))))

;; --- IDENTITY HELPERS (Deterministic IDs) ---

(defn- generate-candle-id [symbol timeframe timestamp]
  (keyword (str "candle/" symbol "/" timeframe "/" timestamp)))

;; --- WRITE FUNCTIONS (SMART PERSISTENCE) ---

(defn- bar->put-op
  "Normaliza um candle para a operacao de escrita do XTDB.
   Valid-Time e' o proprio timestamp da barra."
  [{:keys [symbol timeframe timestamp] :as bar}]
  (let [clean-bar (dissoc bar :type)]
    [::xt/put
     (assoc clean-bar
            :xt/id (generate-candle-id symbol timeframe timestamp)
            :doc-type :market-bar)
     (java.util.Date/from timestamp)]))

(defn ingest-bar!
  "Persists a complete OHLCV bar. Valid-Time is the bar's closing time."
  [node bar]
  (xt/submit-tx node [(bar->put-op bar)]))

(defn ingest-bars!
  "Persists many bars in a SINGLE transaction.

   Um candle por transacao custa caro: o tx-log guarda ~1,5 KB por transacao,
   independentemente do tamanho do documento. Agrupar em lotes de ~500 reduz
   o numero de transacoes em duas ordens de grandeza para o mesmo dado.

   Nota: coage/valide ANTES de chamar esta funcao. Como e' uma transacao unica,
   um documento invalido derruba o lote inteiro."
  [node bars]
  (when (seq bars)
    (xt/submit-tx node (mapv bar->put-op bars))))

(defn sync!
  "Forca o flush/compactacao do stack de armazenamento.

   Sem isto, um processo que encerra logo apos o backfill deixa o store
   dominado por WAL nao compactado (medido: apenas 2 SSTs contra 8 segmentos
   de log). Chamar antes do shutdown torna o tamanho em disco representativo."
  [node]
  (xt/sync node))

(defn ingest-signal!
  "Persists a rich decision context and strategy snapshot."
  [node signal-map timestamp]
  (xt/submit-tx node [[::xt/put
                       (assoc signal-map
                              :xt/id (java.util.UUID/randomUUID)
                              :doc-type :strategy-signal)
                       (java.util.Date/from timestamp)]]))

(defn ingest-trade!
  "Persists a market trade."
  [node trade-data]
  (let [ts (or (:timestamp trade-data) (java.time.Instant/now))
        doc (assoc trade-data
                   :xt/id (java.util.UUID/randomUUID)
                   :doc-type :market-trade)]
    (xt/submit-tx node [[::xt/put doc (java.util.Date/from ts)]])))

(defn ingest-snapshot!
  "Persists an order book snapshot."
  [node snapshot-data]
  (let [ts (or (:timestamp snapshot-data) (java.time.Instant/now))
        doc (assoc snapshot-data
                   :xt/id (java.util.UUID/randomUUID)
                   :doc-type :order-book-snapshot)]
    (xt/submit-tx node [[::xt/put doc (java.util.Date/from ts)]])))

;; --- READ FUNCTIONS (TIME TRAVEL) ---

(defn get-bar-at
  "Time Travel Query."
  [node symbol timeframe timestamp]
  (let [id (generate-candle-id symbol timeframe timestamp)
        ;; Convert Instant to Date for XTDB query context
        db (xt/db node (java.util.Date/from timestamp))]
    (xt/entity db id)))

(defn get-history
  "Retorna os últimos N candles ordenados cronologicamente.
   Vital para cálculo de indicadores (EMA, RSI).

   O XTDB ja' devolve na ordem pedida no :order-by, entao nao ha' reordenacao
   em memoria. O :limit corta no banco, nao depois."
  [node symbol timeframe limit]
  (let [db (xt/db node)
        query {:find '[ts (pull e [*])]
               :in '[symbol tf]
               :where '[[e :symbol symbol]
                        [e :timeframe tf]
                        [e :timestamp ts]
                        [e :doc-type :market-bar]]
               :order-by '[[ts :desc]]
               :limit limit}
        results (xt/q db query symbol timeframe)]
    ;; XTDB retorna DESC (mais novo primeiro); indicadores esperam ASC
    (->> results
         (map second)
         (reverse))))