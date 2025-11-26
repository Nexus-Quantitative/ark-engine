(ns com.nexus-quant.ark-engine.ingestor.core
  (:require [taoensso.carmine :as car]
            [com.nexus-quant.ark-engine.temporal-db.interface :as db]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.core.async :as async :refer [go-loop <! chan close!]]))

(def DEFAULT-CONFIG
  {:redis-conn {:pool {} :spec {:host "localhost" :port 6379}}
   :stream-key "market-events"
   :group-name "ark-ingestor-group"
   :dlq-key    "market-events-dlq"})

(defn- run-cmd [conn cmd-fn & args]
  (car/wcar conn
            (apply cmd-fn args)))

(defn- ensure-group! [conn stream group]
  (try
    (run-cmd conn car/xgroup "CREATE" stream group "$" "MKSTREAM")
    (println "DEBUG: Group created:" group "on" stream)
    (catch Exception e
      (let [msg (.getMessage e)]
        (if (and msg (.contains msg "BUSYGROUP"))
          (println "DEBUG: Group exists (BUSYGROUP):" group)
          (println "DEBUG: Group create error:" msg))))))

(defn- safe-parse [raw-fields]
  (try
    (let [base-map (keywordize-keys (apply hash-map raw-fields))
          data-str (:data base-map)]
      (if (string? data-str)
        {:status :ok :payload (assoc base-map :data (edn/read-string data-str))}
        {:status :ok :payload base-map}))
    (catch Exception e
      {:status :error :reason :malformed-edn :ex e :raw raw-fields})))

(defn- send-to-dlq! [config id raw-fields reason]
  (let [{:keys [redis-conn dlq-key stream-key group-name]} config]
    (println "DEBUG: Sending to DLQ:" id reason)
    (try
      (car/wcar redis-conn
                (apply car/xadd [dlq-key "*" "original-id" id "reason" (str reason) "raw" (pr-str raw-fields)])
                (apply car/xack [stream-key group-name id]))
      (catch Exception e
        (println "ERROR: DLQ Failed!" (.getMessage e))))))

(defn- process-valid-event! [config xtdb-node id event]
  (let [{:keys [redis-conn stream-key group-name]} config
        payload (:data event)
        type    (:type payload)]
    (println "DEBUG: Processing Valid Event:" type)
    (try
      (case type
        :candle (db/ingest-bar! xtdb-node payload)
        :signal (db/ingest-signal! xtdb-node payload (:ts payload))
        (println "DEBUG: Ignored type:" type))

      (run-cmd redis-conn car/xack stream-key group-name id)
      (println "DEBUG: ACKed:" id)

      (catch Exception e
        (println "DEBUG: DB/Proc Failure for ID:" id (.getMessage e))
        (throw e)))))

(defn start!
  ([xtdb-node] (start! xtdb-node DEFAULT-CONFIG))
  ([xtdb-node config]
   (let [final-config                               (merge DEFAULT-CONFIG config)
         {:keys [redis-conn stream-key group-name]} final-config
         consumer-name                              (str "worker-" (java.util.UUID/randomUUID))
         stop-ch                                    (chan)]

     (println "DEBUG: Worker Config -> Stream:" (pr-str stream-key) "Group:" (pr-str group-name))

     (ensure-group! redis-conn stream-key group-name)

     (println "DEBUG: Loop started for" consumer-name)

     ;; Start the async processing loop
     (go-loop []
       (let [[_ port] (async/alts! [stop-ch] :default :continue)]
         (if (= port stop-ch)
           (println "DEBUG: Worker stopped.")
           (do
             (try
               (let [messages (run-cmd redis-conn car/xreadgroup
                                       "GROUP" group-name consumer-name
                                       "BLOCK" "2000" "COUNT" "10"
                                       "STREAMS" stream-key ">")]

                 (when (seq messages)
                   (let [[_ stream-items] (first messages)]
                     (doseq [[id fields] stream-items]
                       (println "DEBUG: Received ID:" id "Fields:" fields)
                       (let [{:keys [status payload reason]} (safe-parse fields)]
                         (if (= status :ok)
                           (process-valid-event! final-config xtdb-node id payload)
                           (send-to-dlq! final-config id fields reason)))))))
               (catch Exception e
                 (println "DEBUG: Critical Loop Error:" (.getMessage e))))

             ;; Small sleep to prevent tight loop
             (<! (async/timeout 10))
             (recur)))))

     ;; Return stop function that closes the channel
     (fn stop-fn []
       (close! stop-ch)))))