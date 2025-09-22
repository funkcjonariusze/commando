(ns commando.impl.utils)

(def exception-message-header "Commando. ")

(def ^:dynamic *debug-mode*
  "Debug mode flag controlled by COMMANDO_DEBUG environment variable.
   When enabled, debug-stack-map functionality is active in find-commands*."
  false)

(def ^{:dynamic true
       :private true
       :doc "For debugging purposes and some mysterious reason of setting it dynamically during execution"}
     *command-map-spec-registry*
  nil)

(defn command-map-spec-registry
  "For debugging purposes and some mysterious reason of setting it dynamically during execution"
  []
  (or *command-map-spec-registry* []))

(defn serialize-exception
  "Serializes errors into data structures."
  [e]
  (cond
    (nil? e) nil
    #?(:clj (instance? Throwable e)
       :cljs (instance? js/Error e))
    #?(:clj (Throwable->map e)
       :cljs (cond-> {:message (.-message e)
                      :type (.. e -constructor -name)}
               ;; Add data if this is an ExceptionInfo
               (satisfies? ExceptionInfo e) (assoc :data (ex-data e))))
    ;; Maps might already be error representations
    (map? e) e
    ;; Default serialization for other types
    :else {:message (str e)}))
