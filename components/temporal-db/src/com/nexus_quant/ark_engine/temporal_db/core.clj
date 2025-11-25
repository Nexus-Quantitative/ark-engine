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
  ;; Example ID: :candle/BTC-USDT/1m/2025-11-24T10:00:00Z
  ;; Deterministic IDs allow O(1) lookup without scanning indexes.
  (keyword (str "candle/" symbol "/" timeframe "/" (str timestamp))))

;; --- WRITE FUNCTIONS (SMART PERSISTENCE) ---

(defn ingest-bar!
  "Persists a complete OHLCV bar. Valid-Time is the bar's closing time."
  [node {:keys [symbol tf ts] :as bar}]
  (let [id (generate-candle-id symbol tf ts)]
    (xt/submit-tx node [[::xt/put
                         (assoc bar :xt/id id :doc-type :market-bar)
                         ts]])))

(defn ingest-signal!
  "Persists a rich decision context and strategy snapshot.
   Valid-Time is the moment the signal was generated."
  [node signal-map timestamp]
  (xt/submit-tx node [[::xt/put
                       (assoc signal-map
                              :xt/id (java.util.UUID/randomUUID)
                              :doc-type :strategy-signal)
                       timestamp]]))

;; --- READ FUNCTIONS (TIME TRAVEL) ---

(defn get-bar-at
  "Returns the bar that existed/was closed at the specified timestamp (Valid Time Query)."
  [node symbol timeframe timestamp]
  (let [id (generate-candle-id symbol timeframe timestamp)
        db (xt/db node timestamp)]
    (xt/entity db id)))