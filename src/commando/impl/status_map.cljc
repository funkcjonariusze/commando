(ns commando.impl.status-map
  (:require
   [malli.core :as malli]))

(def ^:private status-map-message-schema
  [:map [:message [:string {:min 5}]]])

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
   (merge {:status :ok
           :errors []
           :warnings []
           :successes []}
     m)))

(defn status-map-undefined-status
  [status-map]
  (throw (ex-info (str "Status map exception, :status value incorrect: " (:status status-map))
                  {:status (:status status-map)})))

(defn failed? [status-map] (= (:status status-map) :failed))

(defn ok? [status-map] (= (:status status-map) :ok))
