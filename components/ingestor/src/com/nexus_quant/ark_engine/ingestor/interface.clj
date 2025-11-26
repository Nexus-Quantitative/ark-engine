(ns com.nexus-quant.ark-engine.ingestor.interface
  (:require [com.nexus-quant.ark-engine.ingestor.core :as core]))

(defn start!
  "Starts the ingestion worker.
   Arity 2 allows injecting a config map for testing."
  ([xtdb-node]
   (core/start! xtdb-node))
  ([xtdb-node config]
   (core/start! xtdb-node config)))

(defn stop!
  "Stops the worker gracefully."
  [stop-fn]
  (when stop-fn (stop-fn)))



