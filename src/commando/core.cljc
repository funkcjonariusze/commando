(ns commando.core
  (:require
   [commando.impl.dependency       :as dependency]
   [commando.impl.executing        :as executing]
   [commando.impl.finding-commands :as finding-commands]
   [commando.impl.graph            :as graph]
   [commando.impl.registry         :as registry]
   [commando.impl.status-map       :as smap]
   [commando.impl.utils            :as utils]
   [commando.driver.builtin]))

;; -- Registry API --

(defn registry-create
  "Creates a 'Command' registry from a vector of CommandMapSpecs.

   Accepts either:
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

  Example:
   (registry-create
     [commando.commands.builtin/command-from-spec
      commando.commands.builtin/command-fn-spec])"
  [registry]
  (cond
    (registry/built? registry) registry
    (vector? registry) (registry/build registry)
    :else (throw (ex-info "Registry must be a vector or a built registry"
                         {:registry registry}))))

(defn registry-add
  "Adds or replaces a CommandMapSpec in a built registry.
   Identification is by the spec's :type key. If a spec with the same :type
   already exists it is replaced; otherwise the new spec is appended.
   Revalidates the registry.

   Example:
     (-> (registry-create [...])
         (registry-add my-cmd-spec))"
  [built-registry command-map-spec]
  (registry/registry-add built-registry command-map-spec))

(defn registry-remove
  "Removes a CommandMapSpec from a built registry by its :type.
   Revalidates the registry.

   Example:
     (-> (registry-create [...])
         (registry-remove :my/cmd))"
  [built-registry command-map-spec-type]
  (registry/registry-remove built-registry command-map-spec-type))

;; -- Core Steps --
;;
;; Every step below is public and follows the same contract:
;; `(fn [status-map]) => status-map` (`step-use-registry` also takes `registry`,
;; baked into a closure by `steps-pipeline-default`), wrapped in `core-step-safe` —
;; skips itself with a warning if `status-map` is already `:failed`, catches
;; exceptions, records timing under `:stats`. Safe to call directly, or to
;; compose/reorder/replace via `steps-pipeline-default`/`execute-steps` — see those for
;; the caveats of doing so.

(defn step-use-registry
  [status-map registry]
  (smap/core-step-safe status-map "step-use-registry"
    (fn [sm]
      (assoc sm :registry
        (-> (registry-create registry)
            (registry/enrich-runtime-registry))))))

(defn step-find-commands
  [{:keys [instruction registry] :as status-map}]
  (smap/core-step-safe status-map "step-find-commands"
    (fn [sm]
      (let [{:keys [commands trie]} (finding-commands/find-commands instruction registry)]
        (-> sm
          (assoc :internal/cm-list commands)
          (assoc :internal/path-trie trie)
          (smap/status-map-handle-success {:message "Commands were successfully collected"}))))))

(defn step-guard-commands
  "Runs the configured :hook-command-guard-*-fn once, right after step-find-commands
   and before step-build-deps-tree/step-execute-commands! run.

   See
   - `commando.impl.utils/*execute-config*`
   - `commando.utils/hook-reject-commands-fn`"
  [status-map]
  (smap/core-step-safe status-map "step-guard-commands"
    (fn [sm]
      (let [outer?    (= 1 (count (:stack utils/*execute-internals*)))
            config    (utils/execute-config)
            guard-fn  (or (:hook-command-guard-all-fn config)
                        (if outer?
                          (:hook-command-guard-outer-fn config)
                          (:hook-command-guard-inner-fn config)))]
        (if (nil? guard-fn) sm (guard-fn sm))))))

(defn step-build-deps-tree
  "Builds forward dependency graph using the path-trie produced by step-find-commands."
  [{:keys [instruction] :internal/keys [cm-list path-trie] :as status-map}]
  (smap/core-step-safe status-map "step-build-deps-tree"
    (fn [sm]
      (let [fwd (dependency/build-dependency-graph instruction cm-list path-trie)]
        (-> sm
          (assoc :internal/cm-dependency fwd)
          (smap/status-map-handle-success {:message "Dependency map was successfully built"}))))))

(defn step-sort-commands-by-deps
  [status-map]
  (smap/core-step-safe status-map "step-sort-commands-by-deps"
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

(defn step-execute-commands!
  [{:keys [instruction registry]
    :internal/keys [cm-running-order]
    :as status-map}]
  (smap/core-step-safe status-map "step-execute-commands!"
    (fn [sm]
      (binding [utils/*command-map-spec-registry* registry]
        (if (empty? cm-running-order)
          (smap/status-map-handle-success sm {:message "No commands to execute"})
          (let [[updated-instruction error-info cm-results]
                (executing/execute-commands instruction cm-running-order)]
            (if error-info
              (-> sm
                  (assoc :instruction updated-instruction)
                  (assoc :internal/cm-results cm-results)
                  (smap/status-map-handle-error {:message "Command execution failed during evaluation"
                                                 :error (utils/serialize-exception (:original-error error-info))
                                                 :command-path (:command-path error-info)
                                                 :command-type (:command-type error-info)}))
              (-> sm
                  (assoc :instruction updated-instruction)
                  (assoc :internal/cm-results cm-results)
                  (smap/status-map-handle-success {:message "All commands executed successfully"})))))))))

(defn step-prepare-execution-status-map
  "Strips runtime-registry-only commands out of the running order and resets
   the runtime registry back to its pre-execution shape. Must run after
   step-sort-commands-by-deps and before step-execute-commands!."
  [status-map]
  (smap/core-step-safe status-map "step-prepare-execution-status-map"
    (fn [sm]
      (-> sm
        (update :internal/cm-running-order registry/remove-runtime-registry-commands-from-command-list)
        (update :registry registry/reset-runtime-registry)))))

;; -- Flow Control --

(defn step-assert-keys
  "Builds a `:step-assert` fn: throws if `status-map` is missing any of
   `keys`. The throwing is the assert's own job, not `execute-steps`'s —
   see `execute-steps`."
  [step-name keys]
  (fn [status-map]
    (when-let [missing (seq (remove #(contains? status-map %) keys))]
      (throw
        (ex-info
          (str utils/exception-message-header
               "Cannot run " step-name ". Missing required status-map keys: " (vec missing))
          {:step step-name :missing-keys (vec missing)})))))

(defn steps-pipeline-default
  "execute's pipeline as data — an ordered vector of maps:
     :step-name   keyword matching the `step-*` fn it wraps
     :step-fn     `(fn [status-map]) => status-map`
     :step-assert `(fn [status-map])` — a wiring precondition (not a data
                  check): throws if it fails, since that means the step
                  chain itself was assembled wrong, not that the
                  instruction/config is bad.

   `registry` is baked into `:step-use-registry`'s `step-fn` via closure.

   See
   - `commando.core/execute-steps`"
  [registry]
  [{:step-name :step-use-registry
    :step-fn (fn [sm] (step-use-registry sm registry))
    :step-assert (step-assert-keys :step-use-registry #{})}
   {:step-name :step-find-commands
    :step-fn step-find-commands
    :step-assert (step-assert-keys :step-find-commands #{:instruction :registry})}
   {:step-name :step-guard-commands
    :step-fn step-guard-commands
    :step-assert (step-assert-keys :step-guard-commands #{})}
   {:step-name :step-build-deps-tree
    :step-fn step-build-deps-tree
    :step-assert (step-assert-keys :step-build-deps-tree #{:instruction :internal/cm-list :internal/path-trie})}
   {:step-name :step-sort-commands-by-deps
    :step-fn step-sort-commands-by-deps
    :step-assert (step-assert-keys :step-sort-commands-by-deps #{:internal/cm-dependency})}
   {:step-name :step-prepare-execution-status-map
    :step-fn step-prepare-execution-status-map
    :step-assert (step-assert-keys :step-prepare-execution-status-map #{:internal/cm-running-order :registry})}
   {:step-name :step-execute-commands!
    :step-fn step-execute-commands!
    :step-assert (step-assert-keys :step-execute-commands! #{:instruction :registry :internal/cm-running-order})}])

(defn with-execute-context
  "Creates the internal execution context for `execute` (and nested calls),
   preparing `opts` for use by the steps.

   See
   - `commando.impl.utils/*execute-config*`
   - `commando.impl.utils/*execute-internals*`
   - `commando.core/execute`
   - `commando.core/execute-steps`"
  [opts thunk]
  (binding [utils/*execute-internals* (utils/-execute-internals-push (str (random-uuid)))
            utils/*execute-config*    (utils/execute-config-update opts)]
    (thunk)))

(defn execute-steps
  "Runs `steps` over `status-map` — the same mechanism `execute` uses
   internally, exposed for running only part of the pipeline. Must run
   inside `with-execute-context`. Before each step, calls its `:step-assert`
   (skipped once `status-map` is `:failed`) — `execute-steps` doesn't
   interpret what that does; a `:step-assert` throwing is entirely up to
   the step author, e.g. `commando.core/step-assert-keys`.

   Examples
     ;; equivalent to a plain `execute` call
     (with-execute-context nil
       (fn []
         (execute-steps (smap/status-map-pure {:instruction instruction})
           (steps-pipeline-default registry))))

     ;; stop before commands run, inspect, resume later
     (def steps (steps-pipeline-default registry))
     (def halted
       (with-execute-context nil
         (fn []
           (execute-steps (smap/status-map-pure {:instruction instruction})
             (take-while #(not= (:step-name %) :step-execute-commands!) steps)))))

   See
   - `commando.core/steps-pipeline-default`
   - `commando.core/with-execute-context`"
  [status-map steps]
  (when (empty? (:stack utils/*execute-internals*))
    (throw
      (ex-info
        (str
          utils/exception-message-header
          "execute-steps must run inside with-execute-context (or a top-level execute call) — *execute-internals*/*execute-config* are not bound.")
        {:execute-internals utils/*execute-internals*})))
  (reduce
    (fn [status-map-acc {:keys [step-fn step-assert]}]
      (when-not (smap/failed? status-map-acc)
        (step-assert status-map-acc))
      (step-fn status-map-acc))
    status-map
    steps))

(defn execute
  "Evaluates an instruction with a command registry.

   The optional third argument is a config map:
     :error-data-string - (boolean) serialize exception data as strings
     :hook-execute-start - (fn [status-map]) called before execution;
        return value is discarded (pure observer)
     :hook-execute-end   - (fn [status-map]) called after execution;
        return value is discarded (pure observer)
     :hook-command-guard-outer-fn - (fn [status-map] status-map) called once
        right after commands are found, only on the outermost execute call
        (stack depth 1); unlike the hooks above its return value IS used —
        it can call status-map-handle-error to reject the whole execution
        before step-build-deps-tree/step-execute-commands! run. See
        `commando.utils/hook-reject-commands-fn` for a per-command convenience
        helper to call from inside it.
     :hook-command-guard-inner-fn - same contract, only on nested execute
        calls (e.g. from :commando/macro or :commando/resolve)
     :hook-command-guard-all-fn - same contract, on every execute call
        regardless of depth (used instead of the outer/inner keys, not
        combined with them)

   Config keys are inherited by nested execute calls. Inner calls can
   override specific keys — non-overridden keys come from the parent.

   `execute` itself is a thin wrapper around `steps-pipeline-default`/`execute-steps`/
   `with-execute-context` — see those for stopping the pipeline at a chosen
   step (e.g. to inspect `:internal/cm-running-order` before anything runs)
   and resuming it later.

   Examples:
     ;; Full execution
     (execute reg instruction)

     ;; With config
     (execute reg instruction {:error-data-string false})"
  ([registry instruction] (execute registry instruction nil))
  ([registry instruction opts]
   {:pre [(or (vector? registry) (registry/built? registry))]}
   (with-execute-context opts
     (fn []
       (let [start-time (utils/now)
             config     (utils/execute-config)]
         (-> (smap/status-map-pure {:instruction instruction})
           (utils/hook-process (:hook-execute-start config))
           (execute-steps (steps-pipeline-default registry))
           (smap/status-map-add-measurement "execute" start-time (utils/now))
           (utils/hook-process (:hook-execute-end config))
           (assoc :internal/original-instruction instruction)))))))

(defn failed? [status-map] (smap/failed? status-map))

(defn ok? [status-map] (smap/ok? status-map))
