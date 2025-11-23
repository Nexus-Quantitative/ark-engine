(ns com.nexus-quant.ark-engine.connector.core
  (:require [com.nexus-quant.ark-engine.connector.stub :as stub]))

(defn create-connector [config]
  ;Future: Add selection logic based on :exchange-driver
  ;For now, we force the Idempotent Stub to ensure
  ;that risk and orchestration logic are tested first.;

  (stub/create))