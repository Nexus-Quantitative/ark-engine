(require '[com.nexus-quant.ark-engine.candle-aggregator.interface :as agg]
         '[tick.core :as t])

;; 1. Definição de Dados Simulados
;; Vamos criar 3 candles de 1m: 
;; - 14:00 (Começo)
;; - 14:59 (Fim da janela)
;; - 15:00 (Gatilho de fechamento da janela anterior)

(def c1-start
  {:timestamp (t/instant "2025-01-01T14:00:00Z")
   :open 100M :high 105M :low 99M :close 102M :volume 1000M :symbol "BTC/USDT"})

(def c2-end
  {:timestamp (t/instant "2025-01-01T14:59:00Z")
   :open 102M :high 108M :low 101M :close 107M :volume 500M :symbol "BTC/USDT"})

(def c3-next
  {:timestamp (t/instant "2025-01-01T15:00:00Z")
   :open 107M :high 107M :low 106M :close 106M :volume 200M :symbol "BTC/USDT"})

;; 2. Simulação do Loop (Passo a Passo)

(defn run-simulation []
  (println "\n--- INÍCIO DA SIMULAÇÃO (Timeframe 1h) ---")

  ;; Passo 1: Chega o primeiro candle (14:00)
  (println "\n1. Recebendo Candle 14:00...")
  (let [res1 (agg/aggregate nil c1-start "1h")]
    (println "Estado Atual (Memória):" (:state res1))
    (println "Candle Fechado:" (:closed res1)) ;; Deve ser nil (ainda acumulando)

    ;; Passo 2: Chega o último candle da hora (14:59)
    (println "\n2. Recebendo Candle 14:59...")
    (let [res2 (agg/aggregate (:state res1) c2-end "1h")]
      (println "Estado Atual (Memória):" (:state res2))
      ;; Note que o High deve ser 108 (veio do c2) e o Low 99 (veio do c1)
      (println "High Atual:" (:high (:state res2)))
      (println "Candle Fechado:" (:closed res2)) ;; Ainda nil! A hora não virou.

      ;; Passo 3: Chega o primeiro candle da NOVA hora (15:00)
      (println "\n3. Recebendo Candle 15:00 (A Virada)...")
      (let [res3 (agg/aggregate (:state res2) c3-next "1h")]

        (println "\n>>> EVENTO DE FECHAMENTO DETECTADO! <<<")
        (println "Candle Fechado (Output):" (:closed res3))
        ;; Deve imprimir o candle consolidado das 14:00 completas

        (println "Novo Estado (Memória):" (:state res3))
        ;; Deve ser o inicio da vela das 15:00
        ))))

;; 3. Executar
(run-simulation)