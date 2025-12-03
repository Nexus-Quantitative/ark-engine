(ns com.nexus-quant.ark-engine.orchestrator.interface
  (:require [com.nexus-quant.ark-engine.orchestrator.core :as core]))

(defn start! [xtdb-node opts]
  (core/start! xtdb-node opts))

(defn run-once! [xtdb-node opts]
  (core/run-cycle! xtdb-node opts))
