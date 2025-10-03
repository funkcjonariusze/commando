(ns commando.impl.finding-commands
  (:require
   [commando.impl.command-map :as cm]
   [commando.impl.utils :as utils]))

(defn ^:private coll-child-paths
  "Returns child paths for regular collections that should be traversed."
  [value current-path]
  (cond
    (map? value) (for [[k _v] value] (conj current-path k))
    (coll? value) (for [[i _v] (map-indexed vector value)] (conj current-path i))
    :else []))

(defmulti ^:private command-child-paths
  "Returns child paths that should be traversed for a command based on its dependency mode."
  (fn [command-spec _value _current-path] (get-in command-spec [:dependencies :mode])))

(defmethod command-child-paths :default [_command-spec _value _current-path] [])

(defmethod command-child-paths :all-inside [_command-spec value current-path] (coll-child-paths value current-path))

(defn command?
  [{:keys [recognize-fn]
    :as command-spec}
   value]
  (try (recognize-fn value)
       (catch #?(:cljs :default
                 :clj Exception)
         e
         (throw (ex-info (str utils/exception-message-header
                              "Failed while running recognize command on: "
                              (:type command-spec))
                         {:command-spec command-spec
                          :value value
                          :error (utils/serialize-exception e)})))))

(defn command-valid?
  [{:keys [validate-params-fn]
    :as _command-spec}
   value]
  (or (nil? validate-params-fn) (validate-params-fn value)))

(defn ^:private instruction-command-spec
  "Finds and validates a command from registry that matches the given `value`.
   Returns the command-spec if match is found and valid, nil otherwise.
   Throws exception if match is found but validation fails."
  [command-registry value path]
  (some (fn [command-spec]
          (when (command? command-spec value)
            (let [value-valid-return (command-valid? command-spec value)]
              (cond
                (true? value-valid-return) command-spec
                (or
                 (false? value-valid-return)
                 (nil? value-valid-return))
                (throw
                  (ex-info
                    (str
                      "Failed while validating params for " (:type command-spec) ". Check ':validate-params-fn' property for corresponding command with value it was evaluated on.")
                    {:command-type (:type command-spec)
                     :path path
                     :value value}))
                :else
                (throw
                  (ex-info
                    (str
                      "Failed while validating params for " (:type command-spec) ". Check ':validate-params-fn' property for corresponding command with value it was evaluated on.")
                    {:command-type (:type command-spec)
                     :reason value-valid-return
                     :path path
                     :value value}))))))
        command-registry))

(defn find-commands
  "Traverses the instruction tree (BFS algo) and collects all commands defined by the registry."
  [instruction command-registry]
  (loop [queue [[]]
         found-commands []
         debug-stack-map {}]
    (if (empty? queue)
      found-commands
      (let [current-path (first queue)
            remaining-paths (rest queue)
            current-value (get-in instruction current-path)
            debug-stack (if utils/*debug-mode* (get debug-stack-map current-path (list)) (list))]
        (if-let [command-spec (instruction-command-spec command-registry current-value current-path)]
          (let [command (cm/->CommandMapPath
                         current-path
                         (if utils/*debug-mode* (merge command-spec {:__debug_stack debug-stack}) command-spec))
                child-paths (command-child-paths command-spec current-value current-path)
                updated-debug-stack-map (if utils/*debug-mode*
                                          (reduce #(assoc %1 %2 (conj debug-stack command)) debug-stack-map child-paths)
                                          {})]
            (recur (concat remaining-paths child-paths) (conj found-commands command) updated-debug-stack-map))
          ;; No match - traverse children if coll, skip if leaf
          (recur (concat remaining-paths (coll-child-paths current-value current-path))
                 found-commands
                 debug-stack-map))))))
