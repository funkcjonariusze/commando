(ns commando.test-helpers
  (:require
   [commando.core             :as commando]))

(defn remove-stacktrace
  "Example
     (remove-stacktrace
       (commando.impl.utils/serialize-exception
         (ex-info \"LEVEL1\" {:level \"1\"}
           (ex-info \"LEVEL2\" {:level \"2\"}))))
      =>
      {:type \"exception-info\",
       :class \"clojure.lang.ExceptionInfo\",
       :message \"LEVEL1\",
       :cause
       {:type \"exception-info\",
        :class \"clojure.lang.ExceptionInfo\",
        :message \"LEVEL2\",
        :cause nil,
        :data \"{:level \\\"2\\\"}\"},
       :data \"{:level \\\"1\\\"}\"}"
  [exception]
  (-> exception
    (dissoc :stack-trace)
    (update :cause (fn [cause]
                     (when cause
                       (remove-stacktrace cause))))))

(defn status-map-contains-error?
  [status-map error]
  (if (commando/failed? status-map)
    (let [error-lookup-fn (cond
                            (string? error) (fn [e] (= (:message e) error))
                            (map? error) (fn [e] (= e error))
                            (fn? error) error
                            :else nil)]
      (if error-lookup-fn (some? (first (filter error-lookup-fn (:errors status-map)))) false))
    false))


