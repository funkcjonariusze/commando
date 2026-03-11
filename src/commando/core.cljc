(ns commando.core
  (:require
   [commando.impl.dependency       :as deps]
   [commando.impl.executing        :as executing]
   [commando.impl.finding-commands :as finding-commands]
   [commando.impl.graph            :as graph]
   [commando.impl.registry         :as registry]
   [commando.impl.status-map       :as smap]
   [commando.impl.utils            :as utils]))

;; -- Registry API --

(defn registry-create
  "Creates a 'Command' registry from a map or vector of CommandMapSpecs.

   Accepts either:
   - A map of {type -> CommandMapSpec}
   - A vector of CommandMapSpecs (order defines command scan priority)
   - An already-built registry (returned as-is)

   Each command specification (CommandMapSpec) should be a map containing at least:
   - `:type` - a unique keyword identifying the command type
   - `:recognize-fn` - a function to recognize the command in the instruction map
        (fn [element] (and (map? element) (contains? element :your-command-key))
   - `:apply` - a function to execute the command:
        (fn [instruction command-map-obj command-data] ...)
   - `:dependencies` - declare way the command should build dependency
        {:mode :all-inside} - all commands inside the current map are dependencies
        {:mode :none} - no dependencies, the other commands may depend from it.
        {:mode :point :point-key [:commando/from]} - special type of dependency
             which declare that current command depends from the command it refer by
             exampled :commando/from key.

   Additional optional keys can include:
   - `:validate-params-fn` - a function to validate command structures, and catch
          invalid parameters at the analysis stage. Only if the function
          return 'true' it meant that the command structure is valid.
          (fn [data] (throw ...))        => Failure
          (fn [data] {:reason \"why\"})  => Failure
          (fn [data] nil )               => Failure
          (fn [data] false )             => Failure
          (fn [data] true )              => OK

   The function returns a built registry that can be used to resolve Instruction

  Example (map)
   (registry-create
     {:commando/from commando.commands.builtin/command-from-spec
      :commando/fn   commando.commands.builtin/command-fn-spec})

  Example (vector — order defines scan priority)
   (registry-create
     [commando.commands.builtin/command-from-spec
      commando.commands.builtin/command-fn-spec])"
  ([registry]
   (registry-create registry nil))
  ([registry opts]
   (cond
     (registry/built? registry) registry
     (vector? registry) (let [specs-map (into {} (map (juxt :type identity)) registry)
                              order     (mapv :type registry)]
                          (registry/build specs-map (merge opts {:registry-order order})))
     (map? registry) (registry/build registry opts)
     :else (throw (ex-info "Registry must be a map, vector, or a built registry"
                           {:registry registry})))))

(defn registry-assoc
  "Adds or replaces a CommandMapSpec in a built registry.
   The spec is keyed by `command-map-spec-type` and appended to the scan order
   if not already present. Revalidates the registry.

   Example:
     (-> (registry-create {...})
         (registry-assoc :my/cmd my-cmd-spec))"
  [built-registry command-map-spec-type command-map-spec]
  (registry/registry-assoc built-registry command-map-spec-type command-map-spec))

(defn registry-dissoc
  "Removes a CommandMapSpec from a built registry by its type key.
   Updates the scan order accordingly. Revalidates the registry.

   Example:
     (-> (registry-create {...})
         (registry-dissoc :my/cmd))"
  [built-registry command-map-spec-type]
  (registry/registry-dissoc built-registry command-map-spec-type))

;; -- Core Steps --

(defn ^:private use-registry
  [status-map registry]
  (smap/core-step-safe status-map "use-registry"
    (fn [sm]
      (assoc sm :registry
        (-> (registry-create registry)
            (registry/enrich-runtime-registry))))))

(defn ^:private find-commands
  [{:keys [instruction registry] :as status-map}]
  (smap/core-step-safe status-map "find-commands"
    (fn [sm]
      (-> sm
          (assoc :internal/cm-list (finding-commands/find-commands instruction registry))
          (smap/status-map-handle-success {:message "Commands were successfully collected"})))))

(defn ^:private build-deps-tree
  [{:keys [instruction] :internal/keys [cm-list] :as status-map}]
  (smap/core-step-safe status-map "build-deps-tree"
    (fn [sm]
      (try
        (-> sm
            (assoc :internal/cm-dependency (deps/build-dependency-graph instruction cm-list))
            (smap/status-map-handle-success {:message "Dependency map was successfully built"}))
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
          (smap/status-map-handle-error sm (ex-data e)))))))

(defn ^:private sort-commands-by-deps
  [status-map]
  (smap/core-step-safe status-map "sort-commands-by-deps"
    (fn [sm]
      (let [{:keys [sorted cyclic]} (graph/topological-sort (:internal/cm-dependency sm))
            sm (assoc sm :internal/cm-running-order (vec sorted))]
        (if (not-empty cyclic)
          (smap/status-map-handle-error sm
            {:message (str utils/exception-message-header
                           "sort-entities-by-deps. Detected cyclic dependency")
             :cyclic cyclic})
          (smap/status-map-handle-success sm
            {:message (str utils/exception-message-header
                           "sort-entities-by-deps. Entities was sorted and prepare for evaluating")}))))))

(defn ^:private execute-commands!
  [{:keys [instruction registry]
    :internal/keys [cm-running-order]
    :as status-map}]
  (smap/core-step-safe status-map "execute-commands!"
    (fn [sm]
      (binding [utils/*command-map-spec-registry* registry]
        (if (empty? cm-running-order)
          (smap/status-map-handle-success sm {:message "No commands to execute"})
          (let [[updated-instruction error-info] (executing/execute-commands instruction cm-running-order)]
            (if error-info
              (-> sm
                  (assoc :instruction updated-instruction)
                  (smap/status-map-handle-error {:message "Command execution failed during evaluation"
                                                 :error (utils/serialize-exception (:original-error error-info))
                                                 :command-path (:command-path error-info)
                                                 :command-type (:command-type error-info)}))
              (-> sm
                  (assoc :instruction updated-instruction)
                  (smap/status-map-handle-success {:message "All commands executed successfully"})))))))))

(defn ^:private prepare-execution-status-map [status-map]
  (if (smap/failed? status-map)
    status-map
    (-> status-map
      (update :internal/cm-running-order registry/remove-runtime-registry-commands-from-command-list)
      (update :registry registry/reset-runtime-registry))))

(defn ^:private crop-final-status-map [status-map]
  (let [debug? (:debug-result (utils/execute-config))]
   (cond-> status-map
     (false? debug?) (dissoc :internal/cm-running-order)
     (false? debug?) (dissoc :registry))))

;; -- Execute --

(defn execute
  [registry instruction]
  {:pre [(or (map? registry) (vector? registry))]}
  (binding [utils/*execute-internals* (utils/-execute-internals-push (str (random-uuid)))]
    (let [start-time (utils/now)]
      (-> (smap/status-map-pure {:instruction instruction})
        (utils/hook-process (:hook-execute-start (utils/execute-config)))
        (use-registry registry)
        (find-commands)
        (build-deps-tree)
        (sort-commands-by-deps)
        (prepare-execution-status-map)
        (execute-commands!)
        (smap/status-map-add-measurement "execute" start-time (utils/now))
        (utils/hook-process (:hook-execute-end (utils/execute-config)))))))

(defn failed? [status-map] (smap/failed? status-map))
(defn ok? [status-map] (smap/ok? status-map))

