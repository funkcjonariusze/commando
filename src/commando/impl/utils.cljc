(ns commando.impl.utils
  (:require [malli.core :as malli]))

(def exception-message-header "Commando. ")

(def ^:dynamic *debug-mode*
  "When enabled, debug-stack-map functionality is active in find-commands*."
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
    (Throwable->map e)
    ;; Maps might already be error representations
    (map? e) e
    ;; Default serialization for other types
    :else {:message (str e)}))

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


