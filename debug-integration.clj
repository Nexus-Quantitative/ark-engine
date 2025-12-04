;; Debug Integration Script
;; Run this in your REPL to verify the full pipeline:
;; Redis Stream -> Ingestor -> Aggregator -> XTDB

(require '[com.nexus-quant.ark-engine.ingestor.core :as ingestor] :reload)
(require '[com.nexus-quant.ark-engine.temporal-db.core :as db] :reload)
(require '[taoensso.carmine :as car])
(require '[cheshire.core :as json])
(require '[tick.core :as t])
(require '[xtdb.api :as xt])

;; 1. Setup Redis with ISOLATED keys to avoid polluting main stream
(def redis-conn {:pool {} :spec {:host "localhost" :port 6379}})
(def stream-key "debug-market-events")
(def group-name "debug-ingestor-group")

(defn push-event! [event-map]
  (let [json-str (json/generate-string event-map)]
    (car/wcar redis-conn
              (car/xadd stream-key "*" "data" json-str))))

;; 2. Start System with Custom Config
;; We use an in-memory XTDB node for ephemeral testing
(def node (db/start-node! {:store :memory}))
(def stop-ingestor (ingestor/start! node {:stream-key stream-key
                                          :group-name group-name}))

(println "\n🚀 System started (Isolated Mode). Injecting candles...")

;; 3. Inject Sequence of Candles
;; NOTE: Prices must be STRINGS to satisfy the strict domain schema (BigDecimal coercion)

;; Candle 1: 14:00 (Start of Hour)
(push-event! {:type "candle"
              :symbol "BTC/USDT"
              :timeframe "1m"
              :timestamp (str (t/instant "2025-01-01T14:00:00Z"))
              :open "100" :high "105" :low "99" :close "102" :volume "1000"})

(Thread/sleep 500)

;; Candle 2: 14:59 (End of Hour Window)
(push-event! {:type "candle"
              :symbol "BTC/USDT"
              :timeframe "1m"
              :timestamp (str (t/instant "2025-01-01T14:59:00Z"))
              :open "102" :high "108" :low "101" :close "107" :volume "500"})

(Thread/sleep 500)

;; Candle 3: 15:00 (The Trigger)
;; This candle belongs to the NEXT hour, so it should force the Aggregator to close the 14:00 candle.
(push-event! {:type "candle"
              :symbol "BTC/USDT"
              :timeframe "1m"
              :timestamp (str (t/instant "2025-01-01T15:00:00Z"))
              :open "107" :high "107" :low "106" :close "106" :volume "200"})

(println "✅ Candles injected. Waiting 2s for async processing...")
(Thread/sleep 2000)

;; 4. Verify Results in XTDB
(println "\n=== 🔍 XTDB QUERY RESULTS ===")
(println "Expected: 1h Candle at 14:00 with High=108, Low=99, Vol=1500")

(let [results (xt/q (xt/db node)
                    '{:find [ts open high low close vol tf]
                      :where [[e :symbol "BTC/USDT"]
                              [e :timeframe tf]
                              [e :timestamp ts]
                              [e :open open]
                              [e :high high]
                              [e :low low]
                              [e :close close]
                              [e :volume vol]]})]
  (doseq [[ts o h l c v tf] (sort-by first results)]
    (println (format "Found [%s] %s | O:%s H:%s L:%s C:%s V:%s"
                     tf ts o h l c v))))

;; 5. Cleanup
(stop-ingestor)
(println "\n🛑 System stopped.")
