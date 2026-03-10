(ns commando.impl.registry
  "API for registry.

   A registry is a map-based collection of command specifications that define how to
   recognize, validate, and execute commands found in instruction map.

   Input (user-facing):
     (registry/build
       {:commando/fn   cmds/command-fn-spec
        :commando/from cmds/command-from-spec}
       {:registry-order [:commando/from :commando/fn]})

   Output (built registry):
     {:registry           {:commando/fn spec1, :commando/from spec2}
      :registry-order     [:commando/from :commando/fn]
      :registry-validated 1709654400000
      :registry-hash      12345}"
  (:require
   [commando.impl.command-map :as cm]))

(defn- validate-registry
  "Validates all specs in the registry map.
   Returns {:valid? true} or {:valid? false :errors [...]}"
  [specs-map]
  (let [empty-errors (when (empty? specs-map)
                       [{:type :empty-command-specs
                         :message "Registry is empty"}])
        validation-errors (reduce-kv
                            (fn [errors type spec]
                              (if-let [error (:error (cm/validate-command-spec spec))]
                                (conj errors {:type :invalid-spec
                                              :command-map-spec/type type
                                              :message error})
                                (if (not= type (:type spec))
                                  (conj errors {:type :type-mismatch
                                                :command-map-spec/type type
                                                :message (str "Registry key " type " does not match spec :type " (:type spec))})
                                  errors)))
                            []
                            specs-map)
        all-errors (concat validation-errors empty-errors)]
    (if (empty? all-errors)
      {:valid? true}
      {:valid? false :errors (vec all-errors)})))

(def ^:private default-command-value-spec
  {:type :instruction/_value
   :recognize-fn any?
   :apply (fn [_ _ _m] (throw (ex-info "Command :instruction/value should not be evaluated" {})))
   :dependencies {:mode :none}})
(def ^:private default-command-map-spec
  {:type :instruction/_map
   :recognize-fn map?
   :apply (fn [_ _ _m] (throw (ex-info "Command :instruction/map should not be evaluated" {})))
   :dependencies {:mode :all-inside}})
(def ^:private default-command-vec-spec
  {:type :instruction/_vec
   :recognize-fn vector?
   :apply (fn [_ _ _m] (throw (ex-info "Command :instruction/vec should not be evaluated" {})))
   :dependencies {:mode :all-inside}})

(def ^:private internal-command-specs
  [default-command-vec-spec
   default-command-map-spec
   default-command-value-spec])

(defn- compute-registry-order
  "Computes the scan order: ordered keys first, then remaining keys in arbitrary order."
  [specs-map ordered-keys]
  (let [spec-keys (set (keys specs-map))
        valid-ordered (filterv spec-keys ordered-keys)
        remaining (remove (set valid-ordered) (keys specs-map))]
    (into valid-ordered remaining)))

(defn built?
  "Returns true if the given value is a properly built registry map."
  [registry]
  (and
    (map? registry)
    (some? (:registry-validated registry))
    (some? (:registry-hash registry))
    (contains? registry :registry)
    (contains? registry :registry-order)))

(defn build
  "Builds a command registry from a map of {type -> spec}.

   Args:
     specs-map - A map of {:type spec, ...}
     opts      - Optional map with :registry-order [...] for scan ordering

   Returns:
     A validated registry map or throws an error"
  ([specs-map] (build specs-map nil))
  ([specs-map opts]
   (let [validation (validate-registry specs-map)]
     (if (:valid? validation)
       (let [ordered-keys (compute-registry-order specs-map (:registry-order opts))]
         {:registry           specs-map
          :registry-order     ordered-keys
          :registry-validated #?(:clj (System/currentTimeMillis)
                                 :cljs (.now js/Date))
          :registry-hash      (hash specs-map)})
       (throw
         (ex-info "Invalid registry specification"
           {:errors (:errors validation)
            :registry specs-map}))))))

(defn enrich-runtime-registry [built-registry]
  (let [registry        (:registry built-registry)
        registry-order  (:registry-order built-registry)]
    (assoc built-registry
      :registry-runtime
      (into (mapv registry registry-order)
        internal-command-specs))))

(defn reset-runtime-registry [enreached-registry]
  (dissoc enreached-registry :registry-runtime))

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

(defn registry-assoc
  "Adds or replaces a spec in a built registry. Revalidates."
  [built-registry command-map-spec-type command-map-spec]
  (let [new-specs (assoc (:registry built-registry) command-map-spec-type command-map-spec)
        old-order (:registry-order built-registry)
        new-order (if (some #{command-map-spec-type} old-order)
                    old-order
                    (conj old-order command-map-spec-type))]
    (build new-specs {:registry-order new-order})))

(defn registry-dissoc
  "Removes a spec from a built registry. Revalidates."
  [built-registry command-map-spec-type]
  (let [new-specs (dissoc (:registry built-registry) command-map-spec-type)
        new-order (filterv #(not= % command-map-spec-type) (:registry-order built-registry))]
    (build new-specs {:registry-order new-order})))


