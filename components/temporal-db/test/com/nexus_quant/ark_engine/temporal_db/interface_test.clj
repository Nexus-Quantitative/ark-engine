(ns com.nexus-quant.ark-engine.temporal-db.interface-test
  (:require [clojure.test :refer :all]
            [com.nexus-quant.ark-engine.temporal-db.interface :as sut]
            [xtdb.api :as xt]
            [clojure.java.io :as io])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util Date]
           [java.time Instant]))

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
  (testing "Bitemporal ingestion of Market Bars (Candles)"
    (let [timestamp (Instant/parse "2025-01-01T12:00:00Z")
          bar-data  {:symbol "BTC/USDT"
                     :timeframe "1m"
                     :open 90000M :high 91000M :low 89000M :close 90500M
                     :volume 100M
                     :timestamp timestamp}]

      (sut/ingest-bar! *node* bar-data)
      (xt/sync *node*)

      (let [result (sut/get-bar *node* "BTC/USDT" "1m" timestamp)]
        (is (= 90500M (:close result)))))))

(deftest ingest-signal-test
  (testing "Strategy Signals Persistence (Audit Log)"
    (let [now (Instant/now)
          signal {:strategy "volatility-scalper" :decision :buy :confidence 0.9}]

      (sut/ingest-signal! *node* signal now)
      (xt/sync *node*)

      (let [db (xt/db *node*)
            q-res (xt/q db '{:find [e]
                             :where [[e :strategy "volatility-scalper"]
                                     [e :doc-type :strategy-signal]]})]
        (is (not-empty q-res))))))



(deftest time-travel-audit-test
  (testing "Forensic reconstruction of historical states"
    (let [symbol "BTC/USDT"
          timeframe "1m"
          t1 (Instant/parse "2025-01-01T10:00:00Z")
          t2 (Instant/parse "2025-01-01T10:01:00Z")
          t3 (Instant/parse "2025-01-01T10:02:00Z")]
      ;; 1. The History Happens
      (sut/ingest-bar! *node* {:symbol symbol :timeframe timeframe :timestamp t1
                               :close 90000M :open 90000M :high 90000M :low 90000M :volume 100M})
      (sut/ingest-bar! *node* {:symbol symbol :timeframe timeframe :timestamp t2
                               :close 88000M :open 88000M :high 88000M :low 88000M :volume 100M})
      (sut/ingest-bar! *node* {:symbol symbol :timeframe timeframe :timestamp t3
                               :close 92000M :open 92000M :high 92000M :low 92000M :volume 100M})

      (xt/sync *node*)

      ;; 2. The Audit
      (testing "Should not hallucinate data at timestamps without closed candle"
        (let [t-gap (Instant/parse "2025-01-01T10:00:30Z")]
          (is (nil? (sut/get-bar *node* symbol timeframe t-gap)))))

      (testing "Should recover the exact value of T1"
        (let [bar-t1 (sut/get-bar *node* symbol timeframe t1)]
          (is (= 90000M (:close bar-t1)))))

      (testing "Should recover the exact value of T2"
        (let [bar-t2 (sut/get-bar *node* symbol timeframe t2)]
          (is (= 88000M (:close bar-t2)))))

      (testing "Should recover the exact value of T3"
        (let [bar-t3 (sut/get-bar *node* symbol timeframe t3)]
          (is (= 92000M (:close bar-t3))))))))