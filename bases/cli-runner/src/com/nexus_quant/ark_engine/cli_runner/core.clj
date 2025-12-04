(ns com.nexus-quant.ark-engine.cli-runner.core
  (:require [com.nexus-quant.ark-engine.orchestrator.backfill :as backfill]
            [com.nexus-quant.ark-engine.connector.bitget :as bitget]
            [com.nexus-quant.ark-engine.connector.interface :as conn]
            [com.nexus-quant.ark-engine.temporal-db.interface :as db]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn -main [& args]
  (let [command (first args)]
    (case command
      "backfill"
      (let [[_ symbol timeframe limit-str] args]
        (if (and symbol timeframe limit-str)
          (let [limit (Integer/parseInt limit-str)]
            (log/info "Initializing systems for backfill...")
            (with-open [node (db/start-node!)]
              (let [connector (conn/initialize! (bitget/create)
                                                {:api-key "dummy" :secret "dummy" :passphrase "dummy"})]
                (try
                  (backfill/perform-backfill! node connector symbol timeframe limit)
                  (finally
                    (conn/disconnect! connector))))))
          (println "Usage: backfill <symbol> <timeframe> <limit>")))

      (println "Unknown command. Available: backfill"))))
