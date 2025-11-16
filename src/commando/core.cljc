(ns commando.core
  (:require
   [commando.impl.dependency       :as deps]
   [commando.impl.executing        :as executing]
   [commando.impl.finding-commands :as finding-commands]
   [commando.impl.graph            :as graph]
   [commando.impl.registry         :as registry]
   [commando.impl.status-map       :as smap]
   [commando.impl.utils            :as utils]))

(defn create-registry
  "Creates a 'Command' registry from a vector of CommandMapSpecs:

   Each command specification (CommandMapSpec) should be a map containing at least:
   - `:type` - a unique keyword identifying the command type
   - `:recognize-fn` - a function to recognize the command in the instruction map
        (fn [element] (and (map? element) (contains? element :your-command-key))
   - `:apply` - a function to execute the command:
        (fn [instruction command-map-obj command-data] ...)
   - `:dependencies` - declare way the command should build dependency
        {:mode :all-inside} - all commands inside the current map are depednencies
        {:mode :none} - no dependencies, the other commands may depend from it.
        {:mode :point :point-key [:commando/from]} - special type of dependency
             which declare that current command depends from the command it refer by 
             exampled :commando/from key.

   Additional optional keys can include:
   - `:validate-params-fn` - a function to validate command structures, and catch 
          invalid parameters at the anylisis stage. Only if the function 
          return 'true' it ment that the command structure is valid.
          (fn [data] (throw ...))        => Failure
          (fn [data] {:reason \"why\"})  => Failure
          (fn [data] nil )               => Failure
          (fn [data] false )             => Failure
          (fn [data] true )              => OK

   The function returns a built registry that can be used to resolve Instruction 
  
  Example 
   (create-registry 
     [{:type :print :recognize-fn ... :execute-fn ...}
      commando.commands.builtin/command-fn-spec
      commando.commands.builtin/command-apply-spec
      commando.commands.builtin/command-mutation-spec
      commando.commands.builtin/command-resolve-spec])"
  [registry]
  (registry/build (vec registry)))

(defn ^:private use-registry
  [status-map registry]
  (let [start-time (utils/now)
        result     (case (:status status-map)
                     :failed (-> status-map
                                 (smap/status-map-handle-warning {:message "Skip step with registry check"}))
                     :ok (try (-> status-map
                                  (assoc :registry (if (registry/built? registry) registry (create-registry registry))))
                              (catch #?(:clj Exception
                                        :cljs :default)
                                e
                                (-> status-map
                                    (smap/status-map-handle-error {:message "Invalid registry specification"
                                                                   :error (utils/serialize-exception e)})))))]
    (smap/status-map-add-measurement result "use-registry" start-time (utils/now))))

(defn ^:private find-commands
  "Searches the instruction map for commands defined in the registry.
   Returns input map with assoced :internal/cm-list with vector of CommandMapPath objects
    - Commands are recognized by evaluating each registry command's :recognize-fn

   Skips processing if status is :failed.
   Returns :failed status if params validation of the command fail
   - Commands are validated using their :validate-params-fn if present"
  [{:keys [instruction registry]
    :as status-map}]
  (let [start-time (utils/now)
        result     (case (:status status-map)
                     :failed (-> status-map
                                 (smap/status-map-handle-warning {:message "Skipping search for commands due to :failed status"}))
                     :ok (try (-> status-map
                                  (assoc :internal/cm-list (finding-commands/find-commands instruction registry))
                                  (smap/status-map-handle-success {:message "Commands were successfully collected"}))
                              (catch #?(:clj Exception
                                        :cljs :default)
                                e
                                (-> status-map
                                    (smap/status-map-handle-error {:message
                                                                   "Failed during commands search in instruction. See error for details."
                                                                   :error (utils/serialize-exception e)}))))
                     (smap/status-map-undefined-status status-map))]
    (smap/status-map-add-measurement result "find-commands" start-time (utils/now))))

(defn ^:private build-deps-tree
  "Builds a dependency tree by resolving ':commando/from' references in commands.
   Returns status map with :internal/cm-dependency containing mapping from commands to their dependencies."
  [{:keys [instruction]
    :internal/keys [cm-list]
    :as status-map}]
  (let [start-time (utils/now)
        result     (case (:status status-map)
                     :failed (-> status-map
                                 (smap/status-map-handle-warning {:message "Skipping dependency resolution due to :failed status"}))
                     :ok (try (-> status-map
                                  (assoc :internal/cm-dependency (deps/build-dependency-graph instruction cm-list))
                                  (smap/status-map-handle-success {:message "Dependency map was successfully built"}))
                              (catch #?(:clj clojure.lang.ExceptionInfo
                                        :cljs :default)
                                e
                                (-> status-map
                                    (smap/status-map-handle-error (ex-data e)))))
                     (smap/status-map-undefined-status status-map))]
    (smap/status-map-add-measurement result "build-deps-tree" start-time (utils/now))))

(defn ^:private sort-commands-by-deps
  [status-map]
  (let [start-time (utils/now)
        result     (case (:status status-map)
                     :failed (-> status-map
                                 (smap/status-map-handle-warning {:message (str utils/exception-message-header
                                                                                "sort-entities-by-deps. Skipping mandatory step")}))
                     :ok (let [sort-result (graph/topological-sort (:internal/cm-dependency status-map))
                               status-map (assoc status-map :internal/cm-running-order (vec ;; (reverse (:sorted sort-result))
                                                                                         (:sorted sort-result)))]
                           (if (not-empty (:cyclic sort-result))
                             (smap/status-map-handle-error status-map
                                                           {:message (str utils/exception-message-header
                                                                          "sort-entities-by-deps. Detected cyclic dependency")
                                                            :cyclic (:cyclic sort-result)})
                             (smap/status-map-handle-success
                               status-map
                               {:message (str utils/exception-message-header
                                              "sort-entities-by-deps. Entities was sorted and prepare for evaluating")})))
                     (smap/status-map-undefined-status status-map))]
    (smap/status-map-add-measurement result "sort-commands-by-deps" start-time (utils/now))))

(defn ^:private execute-commands!
  "Execute commands based on `:internal/cm-running-order`, transforming the instruction map.

   This function orchestrates command execution by delegating to individual command specs."
  [{:keys [instruction registry]
    :internal/keys [cm-running-order]
    :as status-map}]
  (let [start-time (utils/now)
        result     (binding [utils/*command-map-spec-registry* registry]
                     (cond
                       (smap/failed? status-map)
                       (smap/status-map-handle-warning status-map {:message "Skipping command evaluation due to failed status"})
                       (empty? cm-running-order) (smap/status-map-handle-success status-map {:message "No commands to execute"})
                       :else (let [[updated-instruction error-info] (executing/execute-commands instruction cm-running-order)]
                               (if error-info
                                 (-> status-map
                                     (assoc :instruction updated-instruction)
                                     (smap/status-map-handle-error {:message "Command execution failed during evaluation"
                                                                    :error (utils/serialize-exception (:original-error error-info))
                                                                    :command-path (:command-path error-info)
                                                                    :command-type (:command-type error-info)}))
                                 (-> status-map
                                     (assoc :instruction updated-instruction)
                                     (smap/status-map-handle-success {:message "All commands executed successfully"}))))))]
    (smap/status-map-add-measurement result "execute-commands!" start-time (utils/now))))

(defn build-compiler
  [registry instruction]
  (let [status-map (-> (smap/status-map-pure {:instruction instruction})
                     (utils/hook-process (:hook-execute-start (utils/execute-config)))
                     (use-registry registry)
                     (find-commands)
                     (build-deps-tree)
                     (sort-commands-by-deps))]
    (case (:status status-map)
      :failed (-> status-map
                (smap/status-map-handle-warning {:message (str utils/exception-message-header
                                                            "build-compiler. Error building compiler")}))
      :ok (cond-> status-map
            true (update-in [:internal/cm-running-order] registry/remove-instruction-commands-from-command-vector)
            (false? (:debug-result (utils/execute-config)))
            (select-keys [:uuid
                          :status
                          :registry
                          :internal/cm-running-order
                          :successes
                          :warnings])))))

(defn ^:private compiler->status-map
  "Cause compiler contains only two :registry and :internal/cm-running-order keys
  they have to be added to status-map before it be executed."
  [compiler]
  (if (and (registry/built? (get compiler :registry))
           (contains? compiler :internal/cm-running-order)
           (contains? compiler :status))
    (case (:status compiler)
      :ok (if (true? (:debug-result (utils/execute-config)))
            (->
              (smap/status-map-pure compiler))
            (->
              (smap/status-map-pure (select-keys compiler
                                      [:uuid
                                       :registry
                                       :internal/cm-running-order
                                       :successes
                                       :warnings]))))
      :failed compiler)
    (->
      (smap/status-map-pure compiler)
      (smap/status-map-handle-error {:message "Corrupted compiler structure"}))))

(defn execute
  [registry-or-compiler instruction]
  {:pre [(or (map? registry-or-compiler) (sequential? registry-or-compiler))]}
  (binding [utils/*execute-internals* (utils/-execute-internals-push (str (random-uuid)))]
   (let [ ;; Under (build-compiler) we ment the unfinished status map
         start-time (utils/now)
         status-map-with-compiler (-> (cond
                                        (map? registry-or-compiler)
                                        (->
                                          (compiler->status-map registry-or-compiler))
                                        (sequential? registry-or-compiler)
                                        (->
                                          (build-compiler registry-or-compiler instruction)
                                          (compiler->status-map)))
                                    (assoc :instruction instruction))]
     (cond-> (execute-commands! status-map-with-compiler)
       (false? (:debug-result (utils/execute-config))) (dissoc :internal/cm-running-order)
       (false? (:debug-result (utils/execute-config))) (dissoc :registry)
       :always (smap/status-map-add-measurement "execute" start-time (utils/now))
       :always (utils/hook-process (:hook-execute-end (utils/execute-config)))))))

(defn failed? [status-map] (smap/failed? status-map))
(defn ok? [status-map] (smap/ok? status-map))

