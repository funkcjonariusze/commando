(ns commando.impl.status-map
  (:require
   [commando.impl.utils :as utils]
   [malli.core :as malli]))

(def ^:private status-map-message-schema
  [:map [:message [:string {:min 5}]]])

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

(defn status-map-handle-warning
  [status-map m]
  (update status-map :warnings (fnil conj []) (malli/coerce status-map-message-schema m)))

(defn status-map-handle-error
  [status-map m]
  (-> status-map
      (update :errors (fnil conj []) (malli/coerce status-map-message-schema m))
      (assoc :status :failed)))

(defn status-map-handle-success
  [status-map m]
  (update status-map :successes (fnil conj []) (malli/coerce status-map-message-schema m)))

(defn status-map-pure
  ([] (status-map-pure nil))
  ([m]
   (merge {:uuid (:uuid utils/*execute-internals*)
           :status :ok
           :errors []
           :warnings []
           :successes []
           :stats []}
     m)))

(defn status-map-undefined-status
  [status-map]
  (throw (ex-info (str "Status map exception, :status value incorrect: " (:status status-map))
                  {:status (:status status-map)})))

(defn failed? [status-map] (= (:status status-map) :failed))

(defn ok? [status-map] (= (:status status-map) :ok))


