(ns com.nexus-quant.ark-engine.orchestrator.interface
  (:require [com.nexus-quant.ark-engine.orchestrator.core :as core]
            [com.nexus-quant.ark-engine.orchestrator.backfill :as backfill]))

(defn start! [xtdb-node opts]
  (core/start! xtdb-node opts))

(defn run-once! [xtdb-node opts]
  (core/run-cycle! xtdb-node opts))

(defn perform-backfill! [node connector symbol timeframe limit]
  (backfill/perform-backfill! node connector symbol timeframe limit))
