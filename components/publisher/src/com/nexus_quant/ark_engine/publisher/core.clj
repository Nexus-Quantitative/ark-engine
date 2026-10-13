(ns com.nexus-quant.ark-engine.publisher.core
  "Ponte entre o feed do conector e o Redis Streams.

  Este componente e' o elo que faltava: o `connector/subscribe!` publica candles
  num canal core.async EM MEMORIA, e o `ingestor` consome de um Redis Stream.
  Nada ligava os dois. Aqui o canal vira produtor `XADD market-events`.

  Contrato com o ingestor (components/ingestor/core.clj):
    - chave do stream : \"market-events\"
    - campo           : \"data\" -> STRING JSON
    - decimais        : sempre STRING (o :decimal estrito do Malli rejeita Doubles)
    - :timestamp      : ISO-8601 (para o transformer :instant do domain-model)"
  (:require [com.nexus-quant.ark-engine.connector.interface :as conn]
            [taoensso.carmine :as car]
            [cheshire.core :as json]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log])
  (:gen-class))

;; --- CONFIGURACAO ---

(def DEFAULT-CONFIG
  {:redis-conn  {:pool {} :spec {:host "localhost" :port 6379}}
   :stream-key  "market-events"
   :redis-retry-ms 2000
   :max-stream-len  100000})  ;; aprox. (XADD MAXLEN ~) — evita crescimento infinito

(def DEFAULT-TOPICS
  [{:type :candle :symbol "BTC/USDT"}])

;; --- PUBLICACAO ---

(defn- candle->wire-json
  "Converte o mapa do conector para o JSON que o ingestor espera.

  O conector ja' normaliza para BigDecimal, mas o seu canal pode carregar
  Strings (como o stub faz). Aqui garantimos String em todo decimal."
  [{:keys [data]}]
  (json/generate-string
   (-> data
       (update :open     str)
       (update :high     str)
       (update :low      str)
       (update :close    str)
       (update :volume   str)
       ;; BigDecimals viram string; Instants viram ISO-8601
       (update :timestamp (fn [ts]
                            (cond
                              (instance? java.time.Instant ts) (str ts)
                              (number? ts) (str (java.time.Instant/ofEpochMilli (long ts)))
                              :else (str ts)))) )))

(defn- publish!
  "Faz XADD de um evento no stream. Devolve true/false."
  [redis-conn stream-key max-len event]
  (try
    (let [payload (candle->wire-json event)
          ;; MAXLEN ~ limita o stream: sem isso o Redis cresce sem teto
          args    (if max-len
                    [stream-key "MAXLEN" "~" (str max-len) "*" "data" payload]
                    [stream-key "*" "data" payload])]
      (car/wcar redis-conn (apply car/xadd args))
      true)
    (catch Exception e
      (log/error e "XADD falhou")
      false)))

;; --- ESTATISTICAS (observabilidade minima) ---

(defn- make-stats [] (atom {:published 0 :failed 0 :reconnects 0}))

;; --- O BRIDGE (canal -> redis) ---

(defn- bridge-channel!
  "Loop que drena o canal do conector e publica no Redis.
  Renova o consumer em caso de erro de Redis (retry com espera)."
  [input-ch {::keys [redis-conn stream-key max-stream-len redis-retry-ms]} stats stop-ch]
  (async/thread
    (loop []
      (when-not (async/poll! stop-ch)
        (let [[event port] (async/alts!! [input-ch stop-ch])]
          (cond
            (= port stop-ch) (log/info "Bridge recebeu stop.")
            (nil? event)     (log/info "Canal fechado. Bridge encerrando.")
            :else
            (do
              (if (publish! redis-conn stream-key max-stream-len event)
                (swap! stats update :published inc)
                (do (swap! stats update :failed inc)
                    (Thread/sleep redis-retry-ms)))
              (recur))))))))

;; --- CICLO DE VIDA ---

(defn start!
  "Conecta no feed do exchange e publica no Redis Stream.

  Devolve um mapa de controle com :stop-fn. Deve ser mantido vivo pelo chamador
  (no `-main` fica em @(promise) ate' sinal)."
  [{:keys [connector topics config]}]
  (let [cfg          (merge DEFAULT-CONFIG config)
        producer-conn (:redis-conn cfg)
        stream-key   (:stream-key cfg)
        max-len      (:max-stream-len cfg)
        topics       (or topics DEFAULT-TOPICS)
        input-ch     (async/chan 1024)   ;; buffer: aplica backpressure no WS
        stop-ch      (async/chan)
        stats        (make-stats)]

    (log/info "Publisher iniciando | stream:" stream-key
              "| topicos:" (mapv :symbol topics))

    (conn/subscribe! connector topics input-ch)

    (bridge-channel! input-ch
                     {::redis-conn producer-conn
                      ::stream-key stream-key
                      ::max-stream-len max-len
                      ::redis-retry-ms (:redis-retry-ms cfg)}
                     stats stop-ch)

    ;; Relatorio periodico: prova que esta' publicando de verdade
    (async/go-loop []
      (async/<! (async/timeout 60000))
      (when-not (async/poll! stop-ch)
        (let [{:keys [published failed reconnects]} @stats]
          (log/info (str "[PUBLISHER] publicados=" published
                         " falhas=" failed " reconexoes=" reconnects))
          (recur))))

    {:stop-fn    (fn []
                   (async/close! stop-ch)
                   (async/close! input-ch)
                   (conn/disconnect! connector)
                   (log/info "Publisher parado."))
     :stats      stats
     :input-ch   input-ch}))

;; --- ENTRYPOINT ---

(defn -main [& args]
  (let [;; topics via CLI: BTC/USDT,ETH/USDT — default BTC/USDT
        topics (if (seq args)
                 (mapv (fn [s] {:type :candle :symbol s}) args)
                 DEFAULT-TOPICS)
        addr   (or (System/getenv "REDIS_ADDR") "localhost:6379")
        [host port] (clojure.string/split addr #":")
        host   (or host "localhost")
        port   (Integer/parseInt (or port "6379"))
        connector (conn/initialize! (conn/create-bitget-connector)
                                    {:api-key "dummy" :secret "dummy" :passphrase "dummy"})]
    (log/info "Publisher | redis:" (str host ":" port) "| simbolos:" (mapv :symbol topics))
    (let [{:keys [stop-fn]} (start! {:connector connector
                                     :topics    topics
                                     :config    {:redis-conn {:pool {} :spec {:host host :port port}}}})]
      ;; Bloqueia para sempre; systemd manda SIGTERM e o shutdown-hook limpa.
      (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable stop-fn))
      @(promise))))
