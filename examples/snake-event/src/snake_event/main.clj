(ns snake-event.main
  (:require [snake-event.loop :as loop])
  (:gen-class))

(defn -main
  [& _]
  (loop/run!))
