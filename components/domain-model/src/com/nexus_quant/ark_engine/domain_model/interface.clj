(ns com.nexus-quant.ark-engine.domain-model.interface
  (:require [com.nexus-quant.ark-engine.domain-model.schemas :as schemas]
            [malli.core :as m]
            [malli.error :as me]
            [clojure.string :as str]))

(def Candle schemas/Candle)
(def Tick schemas/Tick)

(defn- coerce [wire-schema core-schema raw-data type-tag]
  (let [coerced (m/decode (m/schema wire-schema {:registry schemas/registry})
                          raw-data
                          schemas/json-transformer)
        enriched (assoc coerced :type type-tag)]

    (if (m/validate core-schema enriched {:registry schemas/registry})
      enriched
      (throw (ex-info (str (str/capitalize (name type-tag)) " Coercion Failed")
                      {:type :schema-violation
                       :errors (me/humanize (m/explain core-schema enriched {:registry schemas/registry}))
                       :raw raw-data
                       :coerced enriched})))))

(defn coerce-candle [raw-data]
  (coerce schemas/WireCandle schemas/Candle raw-data :candle))

(defn coerce-tick [raw-data]
  (coerce schemas/WireTick schemas/Tick raw-data :tick))
