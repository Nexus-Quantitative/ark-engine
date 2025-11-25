(ns com.nexus-quant.ark-engine.temporal-db.interface-test
  (:require [clojure.test :refer :all]
            [com.nexus-quant.ark-engine.temporal-db.interface :as sut]
            [xtdb.api :as xt]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Date]))

;; --- 1. TEST GLOBAL STATE (DYNAMIC VAR) ---
;; This variable will "hold" the database connection during the test.
;; The ^:dynamic allows it to be redefined in each test thread.
(def ^:dynamic *node* nil)

;; --- 2. FILE UTILITIES (SAME AS BEFORE) ---

(defn create-temp-dir []
  (str (.toAbsolutePath (Files/createTempDirectory "ark-test-xtdb" (into-array FileAttribute [])))))

(defn delete-recursively [fname]
  (let [func (fn [func f]
               (when (.isDirectory f)
                 (doseq [f2 (.listFiles f)]
                   (func func f2)))
               (clojure.java.io/delete-file f))]
    (func func (clojure.java.io/file fname))))

;; --- 3. THE FIXTURE (SETUP & TEARDOWN) ---

(defn db-fixture [test-function]
  (let [tmp-dir (create-temp-dir)
        ;; Starts the node with isolated directory
        node    (sut/start-node! {:db-dir tmp-dir})]

    (try
      ;; BINDING: Injects the node into the dynamic variable *node*
      ;; Only within this scope, *node* will be the real connection.
      (binding [*node* node]
        (test-function)) ;; Runs the (deftest ...)

      (finally
        ;; CLEANUP: Ensures closing and cleanup
        (.close node)
        (delete-recursively tmp-dir)))))

;; --- 4. REGISTRATION ---
;; :each -> Runs a new database instance for EACH test (Total Isolation)
;; :once -> Would run once for all (Risk of data dirt between tests)
(use-fixtures :each db-fixture)

;; --- 5. THE CLEAN TESTS ---

(deftest ingest-and-query-bar-test
  ;; We no longer need (with-node ...), we use *node* directly
  (testing "Bitemporal ingestion of Market Bars (Candles)"
    (let [timestamp #inst "2025-01-01T12:00:00Z"
          bar-data  {:symbol "BTC/USDT"
                     :tf     "1m"
                     :o      90000M
                     :h      91000M
                     :l      89000M
                     :c      90500M
                     :v      100M
                     :ts     timestamp}]

      (sut/ingest-bar! *node* bar-data) ;; Use of *node*
      (xt/sync *node*)

      (let [result (sut/get-bar *node* "BTC/USDT" "1m" timestamp)]
        (is (= 90500M (:c result))))

      (let [past-time #inst "2024-12-31T23:59:59Z"
            result (sut/get-bar *node* "BTC/USDT" "1m" past-time)]
        (is (nil? result))))))

(deftest ingest-signal-test
  (testing "Strategy Signals Persistence (Audit Log)"
    (let [now (Date.)
          signal {:strategy "volatility-scalper" :decision :buy :confidence 0.9}]

      (sut/ingest-signal! *node* signal now)
      (xt/sync *node*)

      (let [db (xt/db *node*)
            q-res (xt/q db '{:find [e]
                             :where [[e :strategy "volatility-scalper"]
                                     [e :doc-type :strategy-signal]]})]
        (is (not-empty q-res))))))



(deftest time-travel-audit-test
  (testing "Historical Audit: Reconstruction of price evolution over time"
    ;; SCENARIO: The market moved.
    ;; T1 (10:00): Bitcoin at $90k
    ;; T2 (10:01): Bitcoin fell to $88k
    ;; T3 (10:02): Bitcoin recovered to $92k
    
    (let [symbol "BTC/USDT"
          tf     "1m"
          t1     #inst "2025-01-01T10:00:00Z"
          t2     #inst "2025-01-01T10:01:00Z"
          t3     #inst "2025-01-01T10:02:00Z"]

      ;; 1. The History Happens (Sequential Ingestion)
      (sut/ingest-bar! *node* {:symbol symbol
                               :tf     tf
                               :ts     t1
                               :c      90000M})
      (sut/ingest-bar! *node* {:symbol symbol
                               :tf     tf
                               :ts     t2
                               :c      88000M})
      (sut/ingest-bar! *node* {:symbol symbol
                               :tf     tf
                               :ts     t3
                               :c      92000M})
      
      (xt/sync *node*)

      ;; 2. The Audit (Time Traveling)
      
      (testing "Should recover the exact value of T1 (90k)"
        (let [bar-t1 (sut/get-bar *node* symbol tf t1)]
          (is (= 90000M (:c bar-t1)) "The past T1 was preserved")))

      (testing "Should recover the exact value of T2 (88k)"
        (let [bar-t2 (sut/get-bar *node* symbol tf t2)]
          (is (= 88000M (:c bar-t2)) "The past T2 (Bottom) was preserved")))

      (testing "Should recover the exact value of T3 (92k)"
        (let [bar-t3 (sut/get-bar *node* symbol tf t3)]
          (is (= 92000M (:c bar-t3)) "The present T3 is correct")))
      
      ;; 3. The "Gap" Test (Interpolation or Null?)
      ;; If we ask for the price at 10:00:30 (halfway between T1 and T2),
      ;; our 'get-bar' searches for exact ID. Since the ID contains the candle timestamp,
      ;; it should return nil (because there is no closed candle at 10:00:30 with that ID).
      ;; This confirms we are not accidentally picking "neighbor" data.
      (testing "Should not hallucinate data at timestamps without closed candle"
        (let [t-gap #inst "2025-01-01T10:00:30Z"]
          (is (nil? (sut/get-bar *node* symbol tf t-gap))))))))