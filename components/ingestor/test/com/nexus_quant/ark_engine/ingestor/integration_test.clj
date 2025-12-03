(ns com.nexus-quant.ark-engine.ingestor.integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.nexus-quant.ark-engine.ingestor.core :as sut]
            [com.nexus-quant.ark-engine.ingestor.interface :as ingest]
            [com.nexus-quant.ark-engine.temporal-db.interface :as db]
            [taoensso.carmine :as car]
            [xtdb.api :as xt]
            [cheshire.core :as json] ;; NEW
            [clojure.tools.logging :as log]))

;; --- TEST CONFIGURATION ---

(def test-redis-conn {:pool {} :spec {:host "localhost" :port 6379}})
(def TEST-STREAM "market-events-test")
(def TEST-GROUP "test-group")
(def TEST-DLQ "market-events-dlq-test")

(def TEST-CONFIG
  {:redis-conn test-redis-conn
   :stream-key TEST-STREAM
   :group-name TEST-GROUP
   :dlq-key    TEST-DLQ})

;; --- FIXTURES ---

(defn redis-cleanup-fixture [f]
  ;; Clean up before execution to ensure a blank state
  (car/wcar test-redis-conn
            (apply car/del [TEST-STREAM TEST-DLQ]))
  (f)
  ;; Clean up after execution
  (car/wcar test-redis-conn
            (apply car/del [TEST-STREAM TEST-DLQ])))

(use-fixtures :each redis-cleanup-fixture)

;; --- HELPERS ---

(defn wait-for
  "Polling function. Waits up to 5000ms for pred to be true.
   Catches exceptions during check to allow system to stabilize."
  [pred msg]
  (loop [attempts 50]
    (if (try (pred) (catch Exception _ false))
      true
      (if (zero? attempts)
        (do (println "TIMEOUT WAITING FOR:" msg) false)
        (do (Thread/sleep 100) (recur (dec attempts)))))))

(defn get-pending-count []
  "Safely queries the Pending Entries List (PEL).
   Returns 0 if stream/group doesn't exist yet."
  (let [info (car/wcar test-redis-conn (apply car/xpending [TEST-STREAM TEST-GROUP]))]
    ;; Redis returns [count min-id max-id consumers] or nil if empty/new
    (if (vector? info)
      (or (first info) 0)
      0)))

;; --- TESTS ---

(deftest ^:integration happy-path-ingestion
  (with-open [node (db/start-node! {:store :memory})]
    (car/wcar test-redis-conn (apply car/xgroup ["CREATE" TEST-STREAM TEST-GROUP "$" "MKSTREAM"]))

    (let [stop-worker (ingest/start! node TEST-CONFIG)]
      (try
        ;; 2. ACTION: Publish Valid Candle (JSON)
        (let [timestamp "2025-01-01T12:00:00Z"
              candle {"type" "candle"
                      "symbol" "BTC/USDT"
                      "timeframe" "1m"
                      "open" "99.0" "high" "105.0" "low" "99.0" "close" "100.0"
                      "volume" "1000"
                      "timestamp" timestamp}]

          (println "1. Publishing Valid JSON Candle...")
          (car/wcar test-redis-conn
                    (apply car/xadd [TEST-STREAM "*" "data" (json/generate-string candle)]))

          ;; 3. ASSERTION: Persistence
          (let [check-db (fn []
                           (xt/sync node)
                           (some? (db/get-bar node "BTC/USDT" "1m" (java.time.Instant/parse timestamp))))]
            (is (wait-for check-db "Candle persistence in XTDB")))

          ;; 4. ASSERTION: Ack
          (is (wait-for #(zero? (get-pending-count)) "Message must be ACKed")))

        (finally (ingest/stop! stop-worker))))))

(deftest ^:integration poison-message-handling
  (with-open [node (db/start-node! {:store :memory})]
    (car/wcar test-redis-conn (apply car/xgroup ["CREATE" TEST-STREAM TEST-GROUP "$" "MKSTREAM"]))

    (let [stop-worker (ingest/start! node TEST-CONFIG)]
      (try
        ;; 1. ACTION: Inject Poison (Malformed EDN)
        (println "2. Injecting Poison Message...")
        (car/wcar test-redis-conn (apply car/xadd [TEST-STREAM "*" "data" "{:bad-edn-syntax"]))

        ;; 2. ASSERTION: Moved to DLQ
        ;; We check if the DLQ has exactly 1 message
        (let [check-dlq (fn []
                          (let [msgs (car/wcar test-redis-conn (apply car/xrange [TEST-DLQ "-" "+"]))]
                            (= 1 (count msgs))))]          (is (wait-for check-dlq "Message appearing in DLQ")))

        ;; 3. ASSERTION: Ack (Worker survived and unblocked queue)
        (is (wait-for #(zero? (get-pending-count)) "Poison message ACK"))

        (finally
          (ingest/stop! stop-worker))))))

(deftest ^:integration transient-db-failure-handling
  (with-open [node (db/start-node! {:store :memory})]
    (car/wcar test-redis-conn (apply car/xgroup ["CREATE" TEST-STREAM TEST-GROUP "$" "MKSTREAM"]))

    ;; 1. SIMULATION: Mock ingest-bar! to throw IOException (Transient Failure)
    (with-redefs [db/ingest-bar! (fn [_ _]
                                   (println "SIMULATING DB CRASH!")
                                   (throw (java.io.IOException. "DB Crash!")))]

      (let [stop-worker (ingest/start! node TEST-CONFIG)]
        (try
          ;; 2. ACTION: Publish Valid Candle
          (println "3. Publishing Candle during DB Crash...")
          (car/wcar test-redis-conn (apply car/xadd [TEST-STREAM "*" "data" (json/generate-string {"type" "candle" "symbol" "BTC" "timeframe" "1m" "open" "100" "high" "100" "low" "100" "close" "100" "volume" "1" "timestamp" "2025-01-01T00:00:00Z"})]))

          ;; Wait for processing attempt (worker should fail internally and log error)
          (Thread/sleep 1000)

          ;; 3. ASSERTION: No Ack (Retry logic)
          ;; The pending count MUST be 1. If it's 0, the worker wrongly ACKed a failed processing.
          (let [pending (get-pending-count)]
            (println "DEBUG: Pending Count during Crash:" pending)
            (is (= 1 pending) "Message must remain PENDING (Not Acked) on transient failure"))

          ;; 4. ASSERTION: Not in DLQ (It's valid data, just bad timing)
          (let [dlq (car/wcar test-redis-conn (apply car/xrange [TEST-DLQ "-" "+"]))]
            (is (empty? dlq) "Valid data must NOT go to DLQ even if DB fails"))

          (finally
            (ingest/stop! stop-worker)))))))