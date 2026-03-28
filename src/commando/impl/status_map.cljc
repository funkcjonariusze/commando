(ns commando.impl.status-map
  (:require
   [commando.impl.utils :as utils]
   [malli.core :as malli]))

;;;------
;;; Stats
;;;------

(defn status-map-add-measurement
  "Calculates the duration from `start-time-ns` and `end-time-ns` and appends it as a tuple
   `[stat-key duration-ns formatted-duration]` to the `:stats` vector in the `status-map`."
  [status-map stat-key start-time-ns end-time-ns]
  (let [duration (- end-time-ns start-time-ns)]
    (update status-map :stats (fnil conj [])
      [stat-key
       duration
       (utils/format-time duration)])))

(def ^:private -coercer:status-map-message
  (malli/coercer [:map [:message [:string {:min 5}]]]))

(defn status-map-handle-warning
  [status-map m]
  (update status-map :warnings (fnil conj []) (-coercer:status-map-message m)))

(defn status-map-handle-error
  [status-map m]
  (-> status-map
      (update :errors (fnil conj []) (-coercer:status-map-message m))
      (assoc :status :failed)))

(defn status-map-handle-success
  [status-map m]
  (update status-map :successes (fnil conj []) (-coercer:status-map-message m)))

(defn status-map-pure
  ([] (status-map-pure {}))
  ([m]
   (-> m
     (assoc :uuid (:uuid utils/*execute-internals*))
     (cond->
       (not (contains? m :status))    (assoc :status :ok)
       (not (contains? m :errors))    (assoc :errors [])
       (not (contains? m :warnings))  (assoc :warnings [])
       (not (contains? m :successes)) (assoc :successes [])
       (not (contains? m :stats))     (assoc :stats [])))))

(defn failed? [status-map] (= (:status status-map) :failed))

(defn ok? [status-map] (= (:status status-map) :ok))

;; --------------------
;; Core Pipeline Helper
;; --------------------

(defn core-step-safe
  "Executes a pipeline step with skip-on-failure, error safety net, and timing.
   step-fn receives the status-map and must return an updated status-map.
   If status is :failed, the step is skipped with a warning.
   Uncaught exceptions are converted to :failed status."
  [status-map step-name step-fn]
  (let [start (utils/now)
        result (if (failed? status-map)
                 (status-map-handle-warning status-map
                   {:message (str "Skipping " step-name)})
                 (try
                   (step-fn status-map)
                   (catch #?(:clj Exception :cljs :default) e
                     (status-map-handle-error status-map
                       {:message (str "Failed at " step-name ". See error for details.")
                        :error (utils/serialize-exception e)}))))]
    (status-map-add-measurement result step-name start (utils/now))))

