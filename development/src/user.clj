(ns user
  (:require [clojure.tools.namespace.repl :refer [refresh]]
            [kaocha.repl :as k]))

;; Shortcut to reset the system and reload changed code
(defn reset []
  (refresh))

;; Shortcut to run all tests without leaving the REPL
(defn test-all []
  (k/run-all))

