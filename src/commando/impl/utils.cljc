(ns commando.impl.utils
  (:require [malli.core :as malli]
            [clojure.string :as str]))

(def exception-message-header "Commando. ")

;; ------------------
;; Dynamic Properties
;; ------------------

(def ^:private -execute-config-default
  {:debug-result false
   :error-data-string true
   :hook-execute-end nil
   :hook-execute-start nil})

(def ^:dynamic
  *execute-config*
  "Dynamic configuration for `commando/execute` behavior.
  - `:debug-result` (boolean): When true, adds additional execution
     information to the returned status-map, aiding in instruction analysis.
  - `:error-data-string` (boolean): When true, the `:data` key in
     serialized `ExceptionInfo` (via `commando.impl.utils/serialize-exception`)
     will be a string representation of the data. When false, it will return
     the original map structure.
  - `:hook-execute-start` (fn [status-map]): if not nil, can run procedure
     passed in value.
  - `:hook-execute-end` (fn [status-map]): if not nil, can run procedure
     passed in value.

  Example
    (binding [commando.impl.utils/*execute-config*
              {:debug-result true
               :error-data-string false
               :hook-execute-start (fn [e] (println (:uuid e)))
               :hook-execute-end (fn [e] (println (:uuid e) (:stats e)))}]
       (commando.core/execute
         [commando.commands.builtin/command-from-spec]
         {\"1\" 1
          \"2\" {:commando/from [\"1\"]}
          \"3\" {:commando/from [\"2\"]}}))"
  -execute-config-default)

(def ^:dynamic
  *execute-internals*
  "Dynamic variable to keep context information about the execution
   setup.
   - `:uuid` the unique name of execution, generated everytime the user
     invoke `commando.core/execute`
   - `:stack` in case of user use `commando.commands.builtin/command-macro-spec`,
     or `commando.commands.query-dsl/command-resolve-spec` or any sort of
     commands what invoking `commando.core/execute` inside of parent instruction
     by simulation recursive call, the :stack key will store the invocation stack
     in vector of :uuids"
  {:uuid nil
   :stack []})

(defn -execute-internals-push
  "Update *execute-internals* structure"
  [uuid-execute-identifier]
  (-> *execute-internals*
    (assoc :uuid uuid-execute-identifier)
    (update :stack conj uuid-execute-identifier)))

(defn execute-config
  "Returns the effective configuration for `commando/execute`, getting data from dynamic variable `commando.impl.utils/*execute-config*`"
  []
  (merge -execute-config-default *execute-config*))

(defn hook-process
  "Function will handle a hooks passed from users.
   Available hooks:
    - `:hook-execute-start`,
    - `:hook-execute-end`.

   Read more:
      `commando.impl.utils/*execute-config*`"
  [status-map hook]
  (when hook
    (try
      (hook status-map)
      (catch #?(:clj Exception
                :cljs :default) e
        nil)))
  status-map)

(def ^:dynamic
  *command-map-spec-registry*
  "Dynamic variable what keep the state of processed
  `:registry` value from `status-map`"
  nil)

(defn command-map-spec-registry
  "Return `:registry` value in dynamic scoupe.
   Required to run `commando.core/execute` inside
   of parent execute invocation.

   See
     `commando.core/execute`
     `commando.core/execute-commands!`(binding)"
  [] (or *command-map-spec-registry* []))

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

;; -----------------
;; Performance Tools
;; -----------------

(defn now
  "Returns a high-resolution timestamp in nanoseconds."
  []
  #?(:clj  (System/nanoTime)
     :cljs (* (.now js/performance) 1000000)))

(defn format-time
  "Formats a time `t` in nanoseconds to a string with units (ns, µs, ms, or s)."
  [t]
  (cond
    (< t 1000) (str t "ns")
    (< t 1000000) (str (float (/ t 1000)) "µs")
    (< t 1000000000) (str (float (/ t 1000000)) "ms")
    :else (str (float (/ t 1000000000)) "s")))

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


;; -----------
;; Stats Tools
;; -----------

;; print stats of execution

(defn print-stats
  "Prints a formatted summary of the execution stats from a status-map.

  Example
    (print-stats
      (commando.core/execute
        [commando.commands.builtin/command-from-spec]
        {\"1\" 1
         \"2\" {:commando/from [\"1\"]}
         \"3\" {:commando/from [\"2\"]}}))
    OUT=>
     Execution Stats:
       1  execute-commands! 281.453µs
       =  execute           1.926956ms

    (print-stats
      (binding [commando.impl.utils/*execute-config*
                {:debug-result true}]
        (commando.core/execute
          [commando.commands.builtin/command-from-spec]
          {\"1\" 1
           \"2\" {:commando/from [\"1\"]}
           \"3\" {:commando/from [\"2\"]}})))
    OUT=>
     Execution Stats:
       1  use-registry          141.373µs
       2  find-commands         719.128µs
       3  build-deps-tree       141.061µs
       4  sort-commands-by-deps 112.841µs
       5  execute-commands!     78.601µs
       =  execute               1.466249ms


  See More
   `commando.impl.utils/*execute-config*`"
  ([status-map]
   (print-stats status-map nil))
  ([status-map title]
   (when-let [stats (:stats status-map)]
     (let [max-key-len (apply max 0 (map (comp count name first) stats))]
       (println (str "\nExecution Stats" (when title (str "(" title ")")) ":"))
       (doseq [[index [stat-key _ formatted]] (map-indexed vector stats)]
         (let [key-str (name stat-key)
               padding (str/join "" (repeat (- max-key-len (count key-str)) " "))]
           (println (str
                      "  " (if (= "execute" key-str) "=" (str (inc index)) )
                      "  " key-str " " padding formatted))))))))


;; print stats for all internal executions

(defn ^:private flame-print-stats [stats indent]
  (let [max-key-len (apply max 0 (map (comp count name first) stats))]
    (doseq [[stat-key _ formatted] stats]
      (let [key-str (name stat-key)
            padding (str/join "" (repeat (- max-key-len (count key-str)) " "))]
        (println (str indent
                   "" key-str " " padding formatted))))))

(defn ^:private flame-print [data & [indent]]
  (let [indent (or indent "")]
    (doseq [[k v] data]
      (println (str indent "———" k))
      (when (:stats v)
        (flame-print-stats (:stats v) (str indent "   |")))
      (doseq [[child-k child-v] v
              :when (map? child-v)]
        (when (not= child-k :stats)
          (flame-print {child-k child-v} (str indent "   :")))))))

(defn ^:private flamegraph [data]
  (println "Printing Flamegraph for executes:")
  (flame-print data))

(defn print-deep-stats
  "Function print the flamegraph of internals execution.

   Example
     (defmethod commando.commands.builtin/command-mutation :rand-n
       [_macro-type {:keys [v]}]
       (:instruction
        (commando.core/execute
          [commando.commands.builtin/command-apply-spec]
          {:commando/apply v
           := (fn [n] (rand-int n))})))

     (defmethod commando.commands.builtin/command-macro :sum-n
       [_macro-type {:keys [v]}]
       {:commando/fn (fn [& v-coll] (apply + v-coll))
        :args [v
               {:commando/mutation :rand-n
                :v 200}]})

     (print-deep-stats
       #(commando.core/execute
          [commando.commands.builtin/command-fn-spec
           commando.commands.builtin/command-from-spec
           commando.commands.builtin/command-macro-spec
           commando.commands.builtin/command-mutation-spec]
          {:value {:commando/mutation :rand-n :v 200}
           :result {:commando/macro :sum-n
                    :v {:commando/from [:value]}}}))

     OUT=>
       Printing Flamegraph for executes:
       ———59f2f084-28f6-44fd-bf52-1e561187a2e5
          |execute-commands! 1.123606ms
          |execute           1.92817ms
          :———e4e245ca-194a-43c6-9d7e-9225e0424c46
          :   |execute-commands! 66.344µs
          :   |execute           287.669µs
          :———77de8840-c9d3-4baa-b0d6-8a9806ede29d
          :   |execute-commands! 372.566µs
          :   |execute           721.636µs
          :   :———0aefeb8e-04b2-4e77-b526-6969c08f9bb5
          :   :   |execute-commands! 39.221µs
          :   :   |execute           264.591µs
  "
  [execution-fn]
  (let [stats-state (atom {})
        result
        (binding [*execute-config*
                  {;; :debug-result true
                   :hook-execute-end
                   (fn [e]
                     (swap! stats-state
                       (fn [s]
                         (update-in s (:stack *execute-internals*)
                           #(merge % {:stats (:stats e)})))))}]
          (execution-fn))]
    (flamegraph @stats-state)
    result))
