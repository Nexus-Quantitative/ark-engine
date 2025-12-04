;; Debug Script for Bitget Adapter (Public Data Only)
;; Usage: Run this in your REPL

(require '[com.nexus-quant.ark-engine.connector.interface :as i] :reload)
(require '[com.nexus-quant.ark-engine.connector.bitget :as bitget] :reload)
(require '[clojure.core.async :as a])

(println "🚀 Starting Bitget Public Stream Test...")

;; 1. Create Adapter (No Keys needed for public data)
(def adapter (bitget/create))
(i/initialize! adapter {})

;; 2. Create Output Channel
(def output-ch (a/chan 10))

;; 3. Consume & Print Data
(a/go-loop []
  (when-let [event (a/<! output-ch)]
    (println "✅ RECEIVED:" event)
    (recur)))

;; 4. Subscribe to BTC/USDT (Public Feed)
(println "📡 Subscribing to BTC/USDT...")
(i/subscribe! adapter [{:symbol "BTC/USDT"}] output-ch)

(println "⏳ Waiting for live data... (Check your REPL output)")

;; Block to keep process alive
(loop []
  (Thread/sleep 1000)
  (recur))
