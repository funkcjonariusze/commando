(ns example-reitit
  (:require
   [commando.core               :as commando]
   [commando.commands.builtin   :as command-builtin]
   [commando.commands.query-dsl :as command-query-dsl]
   [reitit.http                 :as http]
   [reitit.interceptor.sieppari :as reitit-sieppari]
   [reitit.ring                 :as ring]
   [ring.middleware.transit     :as ring-transit]))

;; --------------------------
;; 1. Define Commando Handler
;; --------------------------

(defn ^:private commando-handler [request]
  (if-let [instruction-to-run (get request [:body :instruction])]
    (let [result
          (commando/execute
            [command-builtin/command-apply-spec
             command-builtin/command-fn-spec
             command-builtin/command-from-spec
             command-builtin/command-mutation-spec
             command-query-dsl/command-resolve-spec]
            instruction-to-run)]
      (if (= :ok (:status result))
        {:status 200
         :headers {"Content-Type" "application/transit+json; charset=UTF-8"}
         :body (select-keys result [:status :instruction :successes])}
        {:status 500
         :headers {"Content-Type" "application/transit+json; charset=UTF-8"}
         :body (select-keys result [:status :errors :warnings :successes])}))
    {:status 500
     :headers {"Content-Type" "application/transit+json; charset=UTF-8"}
     :body {:status :failed
            :errors [{:message "Instruction is empty"}]}}))

;; ------------------------------------
;; 2. Registering Endpoint With Transit
;; ------------------------------------

(def reitit-handler
  (http/ring-handler
    (http/router
      [["/commando"
        {:post {:handler (-> commando-handler
                           ring-transit/wrap-transit-body
                           ring-transit/wrap-transit-response)}}]])
    (ring/create-default-handler)
    {:executor reitit-sieppari/executor
     :interceptors []}))
