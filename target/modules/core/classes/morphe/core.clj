(ns morphe.core
  (:require [morphe.component :as component]
            [morphe.command :as command]
            [morphe.entity :as entity]
            [morphe.event :as event]
            [morphe.resource :as resource]
            [morphe.runtime :as runtime]
            [morphe.world :as world]))

(def world world/world)
(def spawn entity/spawn)
(def destroy entity/destroy)
(def alive? entity/alive?)
(def component component/component)
(def has-component? component/has-component?)
(def add-component component/add-component)
(def set-component component/set-component)
(def remove-component component/remove-component)
(def update-component component/update-component)
(def event event/event)
(def event? event/event?)
(def command command/command)
(def command? command/command?)
(def get-resource resource/get-resource)
(def set-resource resource/set-resource)
(def update-resource resource/update-resource)
(def remove-resource resource/remove-resource)
(def step runtime/step)
