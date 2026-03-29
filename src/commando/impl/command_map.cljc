(ns commando.impl.command-map
  (:require
   #?(:clj [clojure.pprint])
   #?@(:cljs [[cljs.core :refer [IPrintWithWriter]]])
   [clojure.string :as str]
   [malli.core     :as malli]
   [malli.error    :as merror]
   [malli.util     :as malli-util]))

(defn ^:private cm-generate-id [path] (let [sep ","] (str/join sep (concat ["root"] path))))
(defn ^:private cm-path-string-meta
  [data]
  (some-> data
          :type
          name))

#?(:clj
     (deftype
       ^{:doc
         "Represents a command found in the instruction map at a specific `path`.
            Holds both the path to the command and its command specification data.
            Hash is cached at construction time for fast map/set lookups.

            Equality is based only on path, not on data. This allows commands at the
            same path to be treated as equivalent regardless of their specification."}
       CommandMapPath
       [path data ^int _hash]
       Object
         (equals [this path-obj] (if (instance? CommandMapPath path-obj) (= (.-path this) (.-path path-obj)) false))
         (hashCode [_this] _hash)
         (toString ^String [_]
           (str (cm-generate-id path) (when-let [meta-info (cm-path-string-meta data)] (str "[" meta-info "]"))))))
#?(:clj (do (defmethod print-method CommandMapPath
              [cm-path ^java.io.Writer writer]
              (.write writer (pr-str (.toString cm-path))))
            (defmethod clojure.pprint/simple-dispatch CommandMapPath [cm-path] (print-method cm-path *out*))))


#?(:cljs
     (deftype
       ^{:private false
         :doc
         "Represents a command found in the instruction map at a specific path.
  Holds both the path to the command and its command specification data.
  Hash is cached at construction time for fast map/set lookups.

  Equality is based only on path, not on data. This allows commands at the
  same path to be treated as equivalent regardless of their specification."}
       CommandMapPath
       [path data _hash]
       cljs.core/IEquiv
         (-equiv [this path-obj] (and (instance? CommandMapPath path-obj) (= (.-path this) (.-path path-obj))))
       cljs.core/IHash
         (-hash [_this] _hash)
       IPrintWithWriter
         (-pr-writer [o writer _opts] (-write writer (.toString o)))
       Object
         (equals [this path-obj] (if (instance? CommandMapPath path-obj) (= (.-path this) (.-path path-obj)) false))
         (hashCode [_this] _hash)
         (toString ^String [_]
           (str (cm-generate-id path) (when-let [meta-info (cm-path-string-meta data)] (str "[" meta-info "]"))))))


(defn command-map-path
  "Constructs a CommandMapPath with cached hash for fast map/set lookups."
  [path data]
  (CommandMapPath. path data (hash path)))

(defn command-map? [obj] (instance? CommandMapPath obj))
(defn command-id [p] (when (instance? CommandMapPath p) (.toString p)))
(defn command-path [p] (when (instance? CommandMapPath p) (.-path p)))
(defn command-data [p] (when (instance? CommandMapPath p) (.-data p)))

(defn vector-starts-with?
  "Checks if vector `s` starts with all elements of `prefix`.
   Uses indexed loop instead of seq/take to avoid lazy sequence allocation per call."
  [s prefix]
  (if (or (nil? s) (nil? prefix))
    false
    (let [s-len (count s)
          prefix-len (count prefix)]
      (if (= prefix-len 0)
        true
        (if (< s-len prefix-len)
          false
          (loop [i 0]
            (if (= i prefix-len)
              true
              (if (= (nth s i) (nth prefix i))
                (recur (inc i))
                false))))))))

(defn start-with? [p1 p2] (vector-starts-with? (command-path p1) (command-path p2)))

(def CommandMapSpec
  (malli/schema [:map
                 [:type :keyword]
                 [:recognize-fn fn?]
                 [:validate-params-fn {:optional true} fn?]
                 [:apply fn?]
                 [:dependencies
                  [:merge
                   [:map [:mode [:enum :none :all-inside :point :custom]]]
                   [:multi {:dispatch :mode}
                    [:none [:map]]
                    [:all-inside [:map]]
                    [:point [:map [:point-key [:+ [:or :keyword :string]]]]]]]]]
                {:registry (merge (malli/default-schemas) (malli-util/schemas))}))

(defn validate-command-spec
  "Validates a command spec and returns either the spec or an error map.
   Returns {:error explanation} if validation fails."
  [spec]
  (if (malli/validate CommandMapSpec spec)
    spec
    {:error (str "Invalid command spec for type " (:type spec)
                 (merror/humanize (malli/explain CommandMapSpec spec)))}))

