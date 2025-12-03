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

(defn ingest-bar!
  "Persists a complete OHLCV bar. Valid-Time is the bar's closing time."
  [node {:keys [symbol timeframe timestamp] :as bar}]
  (let [id (generate-candle-id symbol timeframe timestamp)
        ;; Remove domain model discriminator before persisting
        clean-bar (dissoc bar :type)
        ;; XTDB expects java.util.Date, not java.time.Instant
        valid-time (java.util.Date/from timestamp)
        doc (assoc clean-bar :xt/id id :doc-type :market-bar)]
    (xt/submit-tx node [[::xt/put doc valid-time]])))

(defn ingest-signal!
  "Persists a rich decision context and strategy snapshot."
  [node signal-map timestamp]
  (xt/submit-tx node [[::xt/put
                       (assoc signal-map
                              :xt/id (java.util.UUID/randomUUID)
                              :doc-type :strategy-signal)
                       (java.util.Date/from timestamp)]]))

;; --- READ FUNCTIONS (TIME TRAVEL) ---

(defn get-bar-at
  "Time Travel Query."
  [node symbol timeframe timestamp]
  (let [id (generate-candle-id symbol timeframe timestamp)
        ;; Convert Instant to Date for XTDB query context
        db (xt/db node (java.util.Date/from timestamp))]
    (xt/entity db id)))

(defn get-history
  "Retorna os últimos N candles ordenados por tempo.
   Vital para cálculo de indicadores (EMA, RSI)."
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
    ;; Retorna ordenado do mais antigo para o mais novo (Cronológico)
    (->> results
         (map second) ;; Extrai o documento
         (sort-by :timestamp))))