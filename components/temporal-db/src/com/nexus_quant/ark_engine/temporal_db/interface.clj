(ns com.nexus-quant.ark-engine.temporal-db.interface
  (:require [com.nexus-quant.ark-engine.temporal-db.core :as core]))

(defn start-node!
  "Initializes the bitemporal database node.
   
   Arity 0: Uses default configuration (Env Var or 'xtdb-data').
   Arity 1: Accepts a config map (e.g., {:db-dir '/tmp/test'}) for overrides.
   
   Returns:
     System resource (AutoCloseable node)."
  ([] (core/start-node!))
  ([config] (core/start-node! config)))

(defn ingest-bar!
  "Persists aggregated OHLCV bars into the timeline.
   
   Usage:
     (ingest-bar! node {:symbol \"BTC/USDT\" 
                        :tf \"1m\" 
                        :o 90000 :h 91000 :l 89000 :c 90500 
                        :v 150.5 
                        :ts #inst \"...\"})
   
   Note: Valid-Time is set to the bar's timestamp."
  [node bar-data]
  (core/ingest-bar! node bar-data))

(defn ingest-signal!
  "Persists a rich strategy decision context for future auditing.
   
   Args:
     signal: Map containing strategy version, decision logic, and market snapshot.
     ts: The exact instant the decision was made (Valid Time)."
  [node signal ts]
  (core/ingest-signal! node signal ts))

(defn ingest-trade!
  "Persists a market trade."
  [node trade-data]
  (core/ingest-trade! node trade-data))

(defn ingest-snapshot!
  "Persists an order book snapshot."
  [node snapshot-data]
  (core/ingest-snapshot! node snapshot-data))

(defn get-bar
  "Performs a Point-in-Time query to retrieve the exact state of a candle 
   as known at a specific timestamp.
   
   Args:
     node: XTDB node.
     sym: Symbol string (e.g., \"BTC/USDT\").
     tf: Timeframe string (e.g., \"1m\").
     ts: The point in time to query."
  [node sym tf ts]
  (core/get-bar-at node sym tf ts))

(defn get-history [node symbol timeframe limit]
  (core/get-history node symbol timeframe limit))