(ns commando.impl.registry
  "API for registry.

   A registry is a vector-based collection of command specifications that define how to
   recognize, validate, and execute commands found in instruction map.
   Vector order defines the command scan priority.

   Input (user-facing):
     (registry/build
       [cmds/command-from-spec cmds/command-fn-spec])

   Output (built registry):
     {:registry           [command-from-spec command-fn-spec]
      :registry-validated 1709654400000
      :registry-hash      12345}"
  (:require
   [commando.impl.command-map :as cm]))

(defn- validate-registry
  "Validates all specs in the registry vector.
   Returns {:valid? true} or {:valid? false :errors [...]}"
  [specs-vec]
  (let [empty-errors (when (empty? specs-vec)
                       [{:type :empty-command-specs
                         :message "Registry is empty"}])
        validation-errors (reduce
                            (fn [errors spec]
                              (if-let [error (:error (cm/validate-command-spec spec))]
                                (conj errors {:type :invalid-spec
                                              :command-map-spec/type (:type spec)
                                              :message error})
                                errors))
                            []
                            specs-vec)
        type-freq (frequencies (map :type specs-vec))
        dup-errors (reduce-kv
                     (fn [errs t cnt]
                       (if (> cnt 1)
                         (conj errs {:type :duplicate-type
                                     :command-map-spec/type t
                                     :message (str "Duplicate type " t " in registry")})
                         errs))
                     []
                     type-freq)
        all-errors (concat validation-errors empty-errors dup-errors)]
    (if (empty? all-errors)
      {:valid? true}
      {:valid? false :errors (vec all-errors)})))

(def ^:private default-command-value-spec
  {:type :instruction/_value
   :recognize-fn any?
   :apply (fn [_ _ _m] (throw (ex-info "Command :instruction/_value should not be evaluated" {})))
   :dependencies {:mode :none}})
(def ^:private default-command-map-spec
  {:type :instruction/_map
   :recognize-fn map?
   :apply (fn [_ _ _m] (throw (ex-info "Command :instruction/_map should not be evaluated" {})))
   :dependencies {:mode :all-inside}})
(def ^:private default-command-vec-spec
  {:type :instruction/_vec
   :recognize-fn vector?
   :apply (fn [_ _ _m] (throw (ex-info "Command :instruction/_vec should not be evaluated" {})))
   :dependencies {:mode :all-inside}})

(def ^:private internal-command-specs
  [default-command-vec-spec
   default-command-map-spec
   default-command-value-spec])

(defn built?
  "Returns true if the given value is a properly built registry map."
  [registry]
  (and
    (map? registry)
    (some? (:registry-validated registry))
    (some? (:registry-hash registry))
    (vector? (:registry registry))))

(defn build
  "Builds a command registry from a vector of CommandMapSpecs.

   Args:
     specs-vec - A vector of CommandMapSpec maps, each with at least :type

   Returns:
     A validated registry map with :registry vector, or throws an error"
  [specs-vec]
  (let [validation (validate-registry specs-vec)]
    (if (:valid? validation)
      {:registry           specs-vec
       :registry-validated #?(:clj (System/currentTimeMillis)
                              :cljs (.now js/Date))
       :registry-hash      (hash specs-vec)}
      (throw
        (ex-info "Invalid registry specification"
          {:errors (:errors validation)
           :registry specs-vec})))))

(defn enrich-runtime-registry [built-registry]
  (assoc built-registry
    :registry-runtime
    (into (vec (:registry built-registry))
      internal-command-specs)))

(defn reset-runtime-registry [enriched-registry]
  (dissoc enriched-registry :registry-runtime))

(defn remove-runtime-registry-commands-from-command-list [cm-vector]
  (let [cm-type-instruction-defaults
        (into #{} (map :type internal-command-specs))]
   (reduce (fn [acc command-map]
             (if (contains? cm-type-instruction-defaults (:type (cm/command-data command-map)))
               acc (conj acc command-map)))
     [] cm-vector)))

;; ----------------
;; Registry Helpers
;; ----------------

(defn registry-add
  "Adds or replaces a spec in a built registry (identified by :type). Revalidates."
  [built-registry command-map-spec]
  (let [specs-vec (:registry built-registry)
        spec-type (:type command-map-spec)
        existing-idx (first (keep-indexed (fn [i s] (when (= (:type s) spec-type) i)) specs-vec))
        new-vec (if existing-idx
                  (assoc specs-vec existing-idx command-map-spec)
                  (conj specs-vec command-map-spec))]
    (build new-vec)))

(defn registry-remove
  "Removes a spec from a built registry by its :type. Revalidates."
  [built-registry command-map-spec-type]
  (let [new-vec (filterv #(not= (:type %) command-map-spec-type) (:registry built-registry))]
    (build new-vec)))


