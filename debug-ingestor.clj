;; Debug Script - Run this in the REPL to start with logging
;; NOTE: If you have components running, stop them first manually

;; 1. Reload the modified code
(require '[com.nexus-quant.ark-engine.connector.stub :as stub] :reload)
(require '[com.nexus-quant.ark-engine.development.publisher :as pub] :reload)
(require '[com.nexus-quant.ark-engine.ingestor.core :as ingestor] :reload)
(require '[com.nexus-quant.ark-engine.temporal-db.core :as db] :reload)
(require '[xtdb.api :as xt])

;; 2. Start fresh
(def node (db/start-node! {:store :memory}))
(def stop-ingestor (ingestor/start! node))
(def stop-publisher (pub/start-publisher!))

(println "\n⏳ Waiting 5 seconds for data to flow...")
(Thread/sleep 5000)

;; 3. Query to see if data exists
(println "\n=== QUERY RESULTS ===")

(println "\nQuery 1: Testing get-history (Should return data now)")
(let [history (db/get-history node "BTC/USDT" "1m" 5)]
  (println "History Count:" (count history))
  (println "First Item:" (first history)))

(println "\nQuery 2: Looking for :symbol (Raw Query)")
(println (xt/q (xt/db node) '{:find [e] :where [[e :symbol "BTC/USDT"]]}))

(println "\n✅ Verification Complete!")
