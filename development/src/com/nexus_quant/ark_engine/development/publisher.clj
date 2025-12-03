(ns com.nexus-quant.ark-engine.development.publisher
  (:require [com.nexus-quant.ark-engine.connector.stub :as stub]
            [com.nexus-quant.ark-engine.connector.interface :as conn]
            [taoensso.carmine :as car]
            [cheshire.core :as json]
            [cheshire.generate :as json-gen]
            [clojure.core.async :as async :refer [go-loop <! chan close!]]
            [clojure.tools.logging :as log]))

;; Add custom encoder for java.time.Instant
(json-gen/add-encoder java.time.Instant
                      (fn [c jsonGenerator]
                        (.writeString jsonGenerator (.toString c))))

(def DEFAULT-CONFIG
  {:redis-conn {:pool {} :spec {:host "localhost" :port 6379}}
   :stream-key "market-events"})

(defn- publish-to-redis! [redis-conn stream-key event]
  (try
    (if-let [event-type (:type event)]
      (let [event-json (json/generate-string (:data event))
            type-str (name event-type)]
        (car/wcar redis-conn
                  (car/xadd stream-key "*" "type" type-str "data" event-json)))
      (log/warn "Skipping event with nil type:" event))
    (catch Exception e
      (log/error e "Failed to publish to Redis"))))

(defn start-publisher!
  "Starts the Stub connector and bridges it to Redis Streams.
   Returns a stop function."
  ([] (start-publisher! DEFAULT-CONFIG))
  ([config]
   (let [final-config (merge DEFAULT-CONFIG config)
         {:keys [redis-conn stream-key]} final-config
         stub-conn (stub/create)
         output-chan (chan 100)
         stop-ch (chan)]

     ;; Initialize and subscribe
     (conn/initialize! stub-conn {})
     (conn/subscribe! stub-conn
                      [{:type :candle :symbol "BTC/USDT"}]
                      output-chan)

     (log/info "Publisher: Stub -> Redis bridge started")

     ;; Bridge loop
     (go-loop []
       (let [[v port] (async/alts! [output-chan stop-ch])]
         (cond
           (= port stop-ch)
           (do
             (log/info "Publisher: Stopping...")
             (close! output-chan)
             (conn/disconnect! stub-conn))

           (some? v)
           (do
             (publish-to-redis! redis-conn stream-key v)
             (recur))

           :else
           (log/info "Publisher: Channel closed"))))

     ;; Return stop function
     (fn [] (close! stop-ch)))))
