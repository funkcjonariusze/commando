(ns commando.impl.utils
  (:require [malli.core :as malli]))

(def exception-message-header "Commando. ")

;; ------------------
;; Dynamic Properties
;; ------------------

(def ^:private -execute-config-default
  {:debug-result false
   :error-data-string true})

(def ^:dynamic
  *execute-config*
  "Dynamic configuration for `commando/execute` behavior.
  - `:debug-result` (boolean): When true, adds additional execution
     information to the returned status-map, aiding in instruction analysis.
  - `:error-data-string` (boolean): When true, the `:data` key in
     serialized `ExceptionInfo` (via `commando.impl.utils/serialize-exception`)
     will be a string representation of the data. When false, it will return
     the original map structure."
  -execute-config-default)

(defn execute-config
  "Returns the effective configuration for `commando/execute`, getting data from dynamic variable `commando.impl.utils/*execute-config*`"
  []
  (merge -execute-config-default *execute-config*))

(def ^{:dynamic true
       :private true
       :doc "For debugging purposes and some mysterious reason of setting it dynamically during execution"}
  *command-map-spec-registry*
  nil)

(defn command-map-spec-registry
  "For debugging purposes and some mysterious reason of setting it dynamically during execution"
  []
  (or *command-map-spec-registry* []))


;; ------------------
;; Function Resolvers
;; ------------------

(defn resolve-fn
  "Normalize `x` to a function (fn? x) => true.

    - Fn((fn [] ..),:keyword)
    - Vars(#'clojure.core/str, #'str),
    - Symbols('clojure.core/str, 'str)

   :clj  supports fn?/Var/Symbol (with requiring-resolve fallback).
   :cljs only accepts actual functions"
  [x]
  #?(:clj
     (cond
       (fn? x) x
       (keyword? x) x
       (var? x) (let [v @x] (when (fn? v) v))
       (symbol? x)
       (let [v (or
                 ;; already-loaded namespaces
                 (resolve x)
                 ;; try to load the ns
                 (try
                   (requiring-resolve x)
                   (catch Throwable _ nil)))]
         (when (and v (var? v))
           (let [f @v] (when (fn? f) f))))
       :else nil)
     :cljs
     (cond
       (fn? x) x
       (keyword? x) x
       :else nil)))

(defn resolvable-fn? [x]
  (boolean (resolve-fn x)))

(def ResolvableFn
  (malli/deref
   [:fn
    {:error/message #?(:clj "Expected a fn, var of fn, symbol resolving to a fn"
                       :cljs "Expected a fn")}
    (fn [x]
      (some? (resolve-fn x)))]))

;; -----------
;; Error Tools
;; -----------

#?(:clj
   (defn ^:private stacktrace->vec-str [^Throwable t]
     (mapv (fn [^StackTraceElement ste]
             [(.getClassName ste)
              (.getMethodName ste)
              (.getFileName ste)
              (.getLineNumber ste)])
       (.getStackTrace t))))

#?(:clj (defn ^:private exception-dispatch-fn [e] (class e)))

#?(:clj (defmulti ^{:doc
             "Multimethod for serializing exceptions to maps.
Dispatch based on exception class
To add custom serialization for your exception type:

Example
  (defmethod serialize-exception-fn ClassOfException [e]
    {:type \"my-exception\"
     :message (.getMessage e)
     ...})

See
   `commando.impl.utils/serialize-exception`"}
          serialize-exception-fn exception-dispatch-fn))

#?(:clj
   (defmethod serialize-exception-fn java.lang.Throwable [^Throwable t]
     {:type           "throwable"
      :class          (.getName (class t))
      :message        (str (.getMessage t))
      :stack-trace    (stacktrace->vec-str t)
      :cause          (when-let [cause (.getCause t)]
                        (serialize-exception-fn cause))
      :data           nil}))

#?(:clj
   (defmethod serialize-exception-fn java.lang.RuntimeException [^Throwable t]
     {:type           "runtime-exception"
      :class          (.getName (class t))
      :message        (str (.getMessage t))
      :stack-trace    (stacktrace->vec-str t)
      :cause          (when-let [cause (.getCause t)]
                        (serialize-exception-fn cause))
      :data           nil}))

#?(:clj
   (defmethod serialize-exception-fn clojure.lang.ExceptionInfo [^clojure.lang.ExceptionInfo t]
     {:type           "exception-info"
      :class          (.getName (class t))
      :message        (str (.getMessage t))
      :stack-trace    (stacktrace->vec-str t)
      :cause          (when-let [cause (.getCause t)]
                        (serialize-exception-fn cause))
      :data           (if (true? (:error-data-string (execute-config)))
                        (pr-str (ex-data t))
                        (ex-data t))}))

#?(:clj
   (defmethod serialize-exception-fn :default [t]
     (when (instance? java.lang.Throwable t)
       {:type           "generic"
        :class          (.getName (class t))
        :message        (str (.getMessage t))
        :stack-trace    (stacktrace->vec-str t)
        :cause          (when-let [cause (.getCause t)]
                          (serialize-exception-fn cause))
        :data           nil})))

#?(:cljs
   (defn ^:private exception-dispatch-fn [e]
     (cond
       (instance? cljs.core.ExceptionInfo e) :cljs-exception-info
       (instance? js/Error e) :js-error
       :else nil)))

#?(:cljs
   (defn ^:private stacktrace->vec-str [^js/Error e]
     (if-let [stack (.-stack e)] (str stack) nil)))

#?(:cljs
   (defmulti ^{:doc
        "Multimethod for serializing exceptions to maps.
dispatch differentiate two type of exception. Not supposed
to be extended in cljs

See
   `commando.impl.utils/serialize-exception`"}
     serialize-exception-fn exception-dispatch-fn))

#?(:cljs
   (defmethod serialize-exception-fn :cljs-exception-info [^cljs.core.ExceptionInfo e]
     {:type           "exception-info"
      :class          "cljs.core.ExceptionInfo"
      :message        (.-message e)
      :stack-trace    (stacktrace->vec-str e)
      :cause          (when-let [cause (.-cause e)]
                        (serialize-exception-fn cause))
      :data           (if (true? (:error-data-string (execute-config)))
                        (pr-str (.-data e))
                        (.-data e))}))

#?(:cljs
   (defmethod serialize-exception-fn :js-error [^js/Error e]
     {:type           "js-error"
      :class          "js/Error"
      :message        (or (.-message e) "No message")
      :stack-trace    (stacktrace->vec-str e)
      :cause          nil
      :data           nil}))

#?(:cljs
   (defmethod serialize-exception-fn :default [_e]
     nil))

(defn serialize-exception
  "Serializes errors into data structures."
  [e]
  (cond
    (nil? e) nil
    #?(:clj (instance? Throwable e)
       :cljs (instance? js/Error e))
    (serialize-exception-fn e)
    (map? e) e
    :else {:message (str e)}))
