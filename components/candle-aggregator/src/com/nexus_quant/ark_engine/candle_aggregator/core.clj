(ns com.nexus-quant.ark-engine.candle-aggregator.core
  (:require [tick.core :as t]))

;; Mapeamento de Strings de Timeframe para Unidades de Tempo
(def TF-UNITS
  {"1h" :hours
   "4h" :hours ;; Requer lógica especial de módulo, simplificado para horas aqui
   "1d" :days})

(defn- get-bucket-start
  "Arredonda o timestamp para o início da janela do timeframe alvo.
   Ex: 14:59 -> 1h -> 14:00"
  [timestamp timeframe]
  (let [unit (get TF-UNITS timeframe :hours)]
    (t/truncate timestamp unit)))

(defn- init-aggregate
  "Cria o estado inicial de um novo candle maior."
  [candle target-tf]
  (let [bucket-ts (get-bucket-start (:timestamp candle) target-tf)]
    (assoc candle
           :timeframe target-tf
           :timestamp bucket-ts
           :type :candle))) ;; Garante conformidade com Malli

(defn- merge-candle
  "Funde um candle menor (1m) dentro do acumulador maior."
  [acc candle]
  (-> acc
      (update :high max (:high candle))
      (update :low  min (:low candle))
      (assoc  :close    (:close candle)) ;; Close é sempre o último
      (update :volume + (:volume candle))
      ;; Mantém Open e Timestamp originais do acumulador
      ))

(defn process-tick
  "Função Pura de Transição de Estado.
   Args:
     current-state: O candle parcial que está na memória (ou nil).
     new-candle: O candle de 1m que acabou de chegar.
     target-tf: O timeframe alvo (ex: '1h').
   
   Returns:
     {:state  <Novo Acumulador>
      :closed <Candle Fechado para Persistir ou nil>}"
  [current-state new-candle target-tf]

  (if (nil? current-state)
    ;; 1. Cold Start: Primeiro dado do sistema
    {:state (init-aggregate new-candle target-tf)
     :closed nil}

    (let [current-bucket (:timestamp current-state)
          new-bucket     (get-bucket-start (:timestamp new-candle) target-tf)]

      (if (= current-bucket new-bucket)
        ;; 2. Mesma Janela: Apenas atualiza (Merge)
        {:state (merge-candle current-state new-candle)
         :closed nil}

        ;; 3. Janela Mudou: Fecha o anterior, inicia o novo
        {:state (init-aggregate new-candle target-tf)
         :closed current-state})))) 
