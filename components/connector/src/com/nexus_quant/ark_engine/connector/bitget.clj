(ns com.nexus-quant.ark-engine.connector.bitget
  (:require [com.nexus-quant.ark-engine.connector.interface :as i]
            [aleph.http :as http]
            [manifold.stream :as s]
            [manifold.deferred :as d]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [clojure.core.async :as a])
  (:import [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util Base64]
           [java.net URLEncoder]))

;; --- Constants ---
(def ^:const BASE-URL "https://api.bitget.com")
(def ^:const WS-URL "wss://ws.bitget.com/v2/ws/public")

;; --- Helper Functions: Signing & Auth ---

(defn- generate-query-string [params]
  (str/join "&"
            (map (fn [[k v]]
                   (str (name k) "=" (URLEncoder/encode (str v) "UTF-8")))
                 params)))

(defn- hmac-sha256 [key data]
  (let [mac (Mac/getInstance "HmacSHA256")
        secret-key (SecretKeySpec. (.getBytes key "UTF-8") "HmacSHA256")]
    (.init mac secret-key)
    (.encodeToString (Base64/getEncoder) (.doFinal mac (.getBytes data "UTF-8")))))

(defn- sign-request [api-key secret passphrase method path query-params body timestamp]
  (let [query-str (if (seq query-params)
                    (str "?" (generate-query-string query-params))
                    "")
        body-str (if body (json/generate-string body) "")
        prehash-string (str timestamp method path query-str body-str)
        signature (hmac-sha256 secret prehash-string)]
    {:headers {"ACCESS-KEY" api-key
               "ACCESS-SIGN" signature
               "ACCESS-TIMESTAMP" (str timestamp)
               "ACCESS-PASSPHRASE" passphrase
               "Content-Type" "application/json"
               "locale" "en-US"}}))

(defn- get-server-time []
  ;; TODO: Implement fetching server time from Bitget
  (System/currentTimeMillis))

;; --- Helper Functions: Data Normalization ---

(defn- normalize-candle [raw-data symbol]
  ;; Adapts Bitget candle format to domain model
  ;; Bitget format example: ["1625097600000" "34567.89" "34580.00" "34500.00" "34550.00" "123.456"]
  ;; Domain model: {:type :candle :data {:open ... :close ...}}
  (let [[ts open high low close volume] raw-data]
    {:type :candle
     :data {:type "candle"
            :symbol symbol
            :timeframe "1m" ;; Hardcoded for now based on channel
            :timestamp (java.time.Instant/ofEpochMilli (Long/parseLong ts))
            :open (bigdec open)
            :high (bigdec high)
            :low (bigdec low)
            :close (bigdec close)
            :volume (bigdec volume)}}))

(defn- map-timeframe [tf]
  (case tf
    "1m" "1m"
    "5m" "5m"
    "15m" "15m"
    "30m" "30m"
    "1h" "1H"
    "4h" "4H"
    "1d" "1D"
    "1w" "1W"
    tf))

(defn- parse-history-candle [raw symbol timeframe]
  (let [[ts o h l c v] raw]
    {:type "candle"
     :symbol symbol
     :timeframe timeframe
     :timestamp (Long/parseLong ts)
     :open o
     :high h
     :low l
     :close c
     :volume v}))

(defn- fetch-history-impl [symbol timeframe limit]
  (let [endpoint "/api/v2/mix/market/candles"
        params {:symbol symbol
                :productType "umcbl"
                :granularity (map-timeframe timeframe)
                :limit (str limit)} ;; Bitget might expect string
        url (str BASE-URL endpoint)
        resp @(http/get url {:query-params params}) ;; Blocking call
        body (json/parse-string (slurp (:body resp)) true)]

    (if (= "00000" (:code body))
      (map #(parse-history-candle % symbol timeframe) (:data body))
      (throw (ex-info "Bitget API Error" {:response body})))))

;; --- Error Mapping ---

(def ^:const ERROR-CODE-MAP
  {"10013" :rate-limit
   "40019" :insufficient-balance
   "45110" :order-not-found})

(defn- map-error [code msg]
  (get ERROR-CODE-MAP code {:type :unknown :code code :msg msg}))

;; --- WebSocket Logic ---

(defn- format-symbol [s]
  (str/replace s "/" ""))

(defn- connect-ws! [url output-ch state-atom]
  (let [connect-fn (fn [retry-count]
                     (let [backoff (min 30000 (* 1000 (Math/pow 2 retry-count)))]
                       (when (> retry-count 0)
                         (log/info "Reconnecting in" backoff "ms...")
                         (Thread/sleep backoff))

                       (d/chain (http/websocket-client url)
                                (fn [socket]
                                  (println "✅ Bitget WS Connected")
                                  (swap! state-atom assoc :ws-conn socket :connecting? false)

                                  ;; Heartbeat Loop
                                  (future
                                    (loop []
                                      (when (not (s/closed? socket))
                                        (s/put! socket "ping")
                                        (Thread/sleep 20000)
                                        (recur))))

                                  ;; Message Handling
                                  (s/consume
                                   (fn [msg]
                                     (try
                                       (if (= "pong" msg)
                                         (log/debug "Received pong")
                                         (let [payload (json/parse-string msg true)]
                                           (when (not= "pong" payload)
                                             (when-let [data (:data payload)]
                                               (let [symbol (get-in payload [:arg :instId])]
                                                 (doseq [item data]
                                                   (a/put! output-ch (normalize-candle item symbol))))))))
                                       (catch Exception e
                                         (log/error e "Error parsing WS message"))))
                                   socket)

                                  ;; Handle Disconnect
                                  (s/on-closed socket
                                               (fn []
                                                 (println "⚠️ WS Disconnected. Attempting reconnect...")
                                                 (swap! state-atom assoc :ws-conn nil :connecting? true)
                                                 (connect-ws! url output-ch state-atom)))) ;; Recursive reconnect
                                (fn [e]
                                  (println "❌ WS Connection Failed:" (.getMessage e))
                                  (recur (inc retry-count))))))] ;; Retry on initial failure
    (connect-fn 0)))

;; --- Record Implementation ---

(defrecord BitgetExchange [config state-atom]
  i/ExchangeConnector

  (initialize! [this cfg]
    (log/info "Initializing Bitget Adapter with config keys:" (keys cfg))
    (reset! state-atom {:config  cfg
                        :ws-conn nil
                        :connecting? false})
    this)

  (subscribe! [this topics output-channel]
    (let [{:keys [ws-conn connecting?]} @state-atom]
      (cond
        ws-conn
        (let [subs (mapv (fn [t]
                           {:instType "USDT-FUTURES"
                            :channel  "candle1m"
                            :instId   (format-symbol (:symbol t))})
                         topics)
              msg  {:op   "subscribe"
                    :args subs}]
          (s/put! ws-conn (json/generate-string msg))
          (println "✅ Subscribed to:" topics))

        connecting?
        (future
          (Thread/sleep 1000)
          (i/subscribe! this topics output-channel))

        :else
        (do
          (println "⏳ Connecting to Bitget WS...")
          (swap! state-atom assoc :connecting? true)
          (connect-ws! WS-URL output-channel state-atom)
          (future
            (Thread/sleep 1000)
            (i/subscribe! this topics output-channel)))))
    this)

  (submit-order! [this order-params]
    (let [{:keys [config]}                    @state-atom
          {:keys [api-key secret passphrase]} config
          endpoint                            "/api/v2/mix/order/place-order"
          ts                                  (get-server-time)
          headers                             (:headers (sign-request api-key secret passphrase "POST" endpoint nil order-params ts))]

      (d/chain (http/post (str BASE-URL endpoint)
                          {:headers headers
                           :body    (json/generate-string order-params)})
               (fn [resp]
                 (let [body (json/parse-string (slurp (:body resp)) true)]
                   (if (= "00000" (:code body))
                     {:status :ack
                      :data   (:data body)}
                     {:status :rejected
                      :reason (map-error (:code body) (:msg body))}))))))

  (cancel-order! [this order-id symbol]
    (let [{:keys [config]}                    @state-atom
          {:keys [api-key secret passphrase]} config
          endpoint                            "/api/v2/mix/order/cancel-order"
          params                              {:symbol  symbol
                                               :orderId order-id}
          ts                                  (get-server-time)
          headers                             (:headers (sign-request api-key secret passphrase "POST" endpoint nil params ts))]

      (d/chain (http/post (str BASE-URL endpoint)
                          {:headers headers
                           :body    (json/generate-string params)})
               (fn [resp]
                 (let [body (json/parse-string (slurp (:body resp)) true)]
                   (if (= "00000" (:code body))
                     true
                     (do (log/error "Cancel failed:" body) false)))))))

  (get-portfolio-state [this]
    (let [{:keys [config]}                    @state-atom
          {:keys [api-key secret passphrase]} config
          endpoint                            "/api/v2/mix/account/account"
          params                              {:productType "umcbl"} ;; USDT-M Futures
          ts                                  (get-server-time)
          headers                             (:headers (sign-request api-key secret passphrase "GET" endpoint params nil ts))]

      (d/chain (http/get (str BASE-URL endpoint)
                         {:headers      headers
                          :query-params params})
               (fn [resp]
                 (let [body (json/parse-string (slurp (:body resp)) true)]
                   (if (= "00000" (:code body))
                     {:total-equity (bigdec (get-in body [:data :available])) ;; Verify path
                      :positions    []} ;; Parse positions
                     (throw (ex-info "Failed to fetch portfolio" {:response body}))))))))

  (disconnect! [this]
    (when-let [ws (:ws-conn @state-atom)]
      (s/close! ws))
    (log/info "Bitget Adapter Disconnected"))

  (fetch-history [this symbol timeframe limit]
    (fetch-history-impl symbol timeframe limit)))

(defn create []
  (->BitgetExchange {} (atom {})))
