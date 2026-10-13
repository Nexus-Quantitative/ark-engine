(ns com.nexus-quant.ark-engine.cli-runner.core
  (:require [com.nexus-quant.ark-engine.orchestrator.interface :as ord]
            [com.nexus-quant.ark-engine.connector.interface :as conn]
            [com.nexus-quant.ark-engine.temporal-db.interface :as db]
            [com.nexus-quant.ark-engine.ingestor.interface :as ingest]
            [com.nexus-quant.ark-engine.publisher.interface :as publish]
            [clojure.tools.logging :as log])
  (:gen-class))

;; --- HELPERS ---

(defn- redis-addr
  "Le REDIS_ADDR (host:porta) com fallback localhost:6379."
  []
  (let [[host port] (clojure.string/split (or (System/getenv "REDIS_ADDR")
                                              "localhost:6379")
                                          #":")]
    {:host (or host "localhost")
     :port (Integer/parseInt (or port "6379"))}))

(defn- redis-conn []
  {:pool {} :spec (redis-addr)})

(defn- symbols-from-env
  "Le ARK_SYMBOLS (ex: BTC/USDT,ETH/USDT) com fallback BTC/USDT."
  []
  (let [raw (or (System/getenv "ARK_SYMBOLS") "BTC/USDT")]
    (mapv clojure.string/trim (clojure.string/split raw #","))))

(defn- keep-alive!
  "Bloqueia a thread principal para sempre. Um shutdown-hook cuida da limpeza,
  de modo que SIGTERM (systemd) encerra o processo de forma ordenada."
  [stop!]
  (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop!))
  (log/info "Servico ativo. Aguardando sinal de parada...")
  @(promise))

;; --- COMANDOS ---

(defn- cmd-backfill [args]
  (let [[_ symbol timeframe limit-str] args]
    (if (and symbol timeframe limit-str)
      (let [limit (Integer/parseInt limit-str)]
        (log/info "Initializing systems for backfill...")
        (with-open [node (db/start-node!)]
          (let [connector (conn/initialize! (conn/create-bitget-connector)
                                            {:api-key "dummy" :secret "dummy" :passphrase "dummy"})]
            (try
              (ord/perform-backfill! node connector symbol timeframe limit)
              (finally
                (conn/disconnect! connector))))))
      (println "Usage: backfill <symbol> <timeframe> <limit>"))))

(defn- cmd-ingest
  "Consome o Redis Stream e persiste no XTDB. Roda ate' receber sinal."
  []
  (let [node (db/start-node!)
        stop (ingest/start! node {:redis-conn (redis-conn)
                                  :stream-key (or (System/getenv "ARK_STREAM") "market-events")
                                  :group-name (or (System/getenv "ARK_GROUP") "ark-ingestor-group")
                                  :dlq-key    (or (System/getenv "ARK_DLQ") "market-events-dlq")})]
    (keep-alive! (fn []
                   (ingest/stop! stop)
                   (.close node)))))

(defn- cmd-publish
  "Le o feed do exchange e publica no Redis Stream (produtor do pipeline)."
  [args]
  (let [symbols (if (seq args) (vec args) (symbols-from-env))
        addr    (redis-addr)
        connector (conn/initialize! (conn/create-bitget-connector)
                                    {:api-key "dummy" :secret "dummy" :passphrase "dummy"})
        stop (publish/start! {:connector connector
                              :topics    (mapv (fn [s] {:type :candle :symbol s}) symbols)
                              :config    {:redis-conn {:pool {} :spec addr}
                                          :stream-key (or (System/getenv "ARK_STREAM") "market-events")}})]
    (log/info "Publicando:" symbols)
    (keep-alive! (fn [] (stop)))))

;; --- ENTRYPOINT ---

(defn -main [& args]
  (let [command (first args)
        rest    (rest args)]
    (case command
      "backfill" (cmd-backfill args)
      "ingest"   (cmd-ingest)
      "publish"  (cmd-publish rest)
      (println (str "Usage:\n"
                    "  backfill <symbol> <timeframe> <limit>\n"
                    "  ingest                              (consome Redis Stream -> XTDB)\n"
                    "  publish [symbol ...]                (feed exchange -> Redis Stream)")))))
