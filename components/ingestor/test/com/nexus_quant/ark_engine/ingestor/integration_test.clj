(ns com.nexus-quant.ark-engine.ingestor.integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.nexus-quant.ark-engine.ingestor.core :as sut]
            [com.nexus-quant.ark-engine.ingestor.interface :as ingest]
            [com.nexus-quant.ark-engine.temporal-db.interface :as db]
            [taoensso.carmine :as car]
            [xtdb.api :as xt]
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
    ;; 0. SETUP: Create stream & group explicitly before worker starts
    ;; This prevents race conditions where worker tries to read before group exists
    (car/wcar test-redis-conn (apply car/xgroup ["CREATE" TEST-STREAM TEST-GROUP "$" "MKSTREAM"]))

    (let [stop-worker (ingest/start! node TEST-CONFIG)]
      (try
        (let [ts #inst "2025-01-01T12:00:00Z"
              candle {:type :candle :symbol "BTC/USDT" :c 100.0M :tf "1m" :ts ts}]

          ;; 1. ACTION: Publish Valid Candle
          (println "1. Publishing Valid Candle...")
          (car/wcar test-redis-conn (apply car/xadd [TEST-STREAM "*" "data" (pr-str candle)]))

          ;; 2. ASSERTION: Persistence (Async Check)
          (let [check-db (fn []
                           ;; Force sync to ensure XTDB indexer catches up
                           (xt/sync node (java.time.Duration/ofSeconds 1))
                           (some? (db/get-bar node "BTC/USDT" "1m" ts)))]
            (is (wait-for check-db "Candle persistence in XTDB")))

          ;; 3. ASSERTION: Acknowledgement
          ;; Message should be removed from pending list (ACKed)
          (let [check-ack (fn [] (zero? (get-pending-count)))]
            (is (wait-for check-ack "Redis Message ACK"))))

        (finally
          (ingest/stop! stop-worker))))))

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
          (car/wcar test-redis-conn (apply car/xadd [TEST-STREAM "*" "data" (pr-str {:type :candle :symbol "BTC" :c 100M})]))

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