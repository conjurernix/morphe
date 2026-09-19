(ns morphe.application-test
  (:require [clojure.test :refer [deftest is testing]]
            [morphe.application :as application]
            [morphe.core :as game]))

(deftest default-application-events-update-adapter-state
  (let [initial (application/create {:width 800 :height 600})
        focused (:application
                  (application/handle-event
                    initial {} (application/focus-changed false) nil))
        resized (:application
                  (application/handle-event
                    focused {} (application/resized 1280 720) nil))
        closed (:application
                 (application/handle-event
                   resized {} (application/quit-requested) nil))]
    (is (false? (:focused? focused)))
    (is (= [1280 720] ((juxt :width :height) resized)))
    (is (false? (:running? closed)))))

(deftest content-scale-events-update-application-state
  (let [initial (application/create {:content-scale {:x 1 :y 1}})
        scaled (:application
                (application/handle-event
                  initial {} (application/content-scale-changed 2 1.5) nil))]
    (is (= {:x 1.0 :y 1.0} (:content-scale initial)))
    (is (= {:x 2.0 :y 1.5} (:content-scale scaled)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/content-scale-changed 0 1)))))

(deftest callbacks-return-game-events-and-controls
  (let [handler (fn [state _game-state {:keys [type]}]
                  {:app (update state :calls (fnil inc 0))
                   :events [(game/event [:app/observed type])]
                   :controls [[:app/cancel-quit] [:app/pause]]})
        result (application/handle-event
                 (application/create {:state {:calls 0}})
                 {}
                 (application/quit-requested)
                 handler)]
    (is (:running? (:application result)))
    (is (:paused? (:application result)))
    (is (= {:calls 1} (-> result :application :state)))
    (is (= [(game/event [:app/observed :morphe.app/quit-requested])]
           (:events result)))))

(deftest callbacks-run-while-paused-and-can-resolve-events
  (let [pause-handler (fn [state _game-state _event]
                        {:app state :events [] :controls [[:app/pause]]})
        paused (:application
                 (application/handle-event
                   (application/create) {}
                   (application/focus-changed false)
                   pause-handler))
        resume-handler (fn [state _game-state _event]
                         {:app state :events [] :controls [[:app/resume]]})
        resumed (:application
                  (application/handle-event
                    paused {} (application/focus-changed true)
                    resume-handler))]
    (is (:paused? paused))
    (is (false? (:paused? resumed)))))

(deftest controls-can-run-outside-window-callbacks
  (let [initial (application/create)
        paused (application/apply-controls initial [[:app/pause]])
        stopped (application/apply-controls paused [[:app/resume] [:app/quit]])]
    (is (:paused? paused))
    (is (false? (:paused? stopped)))
    (is (false? (:running? stopped)))))

(deftest ordered-events-accumulate-callback-output
  (let [handler (fn [state _game-state event]
                  {:app state
                   :events [(game/event [::observed (:type event)])]
                   :controls []})
        result (application/handle-events
                 (application/create)
                 {}
                 [(application/focus-changed false)
                  (application/resized 640 480)]
                 handler)]
    (is (= [640 480]
           ((juxt :width :height) (:application result))))
    (is (= 2 (count (:events result))))))

(deftest error-actions-are-explicit
  (let [error (ex-info "failure" {})]
    (is (= :rethrow (application/error-action nil error {:phase :runtime})))
    (is (= :close
           (application/error-action
             (fn [_throwable _data] :close)
             error
             {:phase :runtime})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/error-action
                   (fn [_throwable _data] :ignore)
                   error
                   {:phase :runtime})))))

(deftest application-boundaries-reject-invalid-data
  (testing "events"
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/focus-changed :yes)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/resized 0 100))))
  (testing "callback results"
    (is (thrown? clojure.lang.ExceptionInfo
                 (application/handle-event
                   (application/create) {} (application/quit-requested)
                   (fn [_ _ _] {:app {} :events [:bad] :controls []}))))))
