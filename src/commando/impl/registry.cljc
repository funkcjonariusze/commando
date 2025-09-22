(ns commando.impl.registry
  "API for registry.
   
   A registry is a collection of command specifications that define how to
   recognize, validate, and execute commands found in instruction map."
  (:require
   [commando.impl.command-map :as cm]))

(defn- find-duplicate-types
  "Returns a seq of duplicate :type values in the given command specs."
  [command-specs]
  (->> command-specs
    (map :type)
    (frequencies)
    (filter (fn [[_ count]] (> count 1)))
    (map first)))

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
(def ^:private -cm-type-instruction-defaults
  (into #{}
   (map :type
     [default-command-vec-spec
      default-command-map-spec
      default-command-value-spec])))

(defn attach-instruction-commands [registry]
  (let [registry-meta (meta registry)]
    (with-meta 
      (into (vec registry)
        [default-command-vec-spec
         default-command-map-spec
         default-command-value-spec])
      registry-meta)))

(defn detach-instruction-commands [registry]
  (let [registry-meta (meta registry)]
    (with-meta
      (reduce (fn [acc e]
                (if 
                    (contains?
                      #{default-command-vec-spec
                        default-command-map-spec
                        default-command-value-spec}
                      e)
                  acc
                  (conj acc e)))
        []
        registry)
      registry-meta)))

(defn remove-instruction-commands-from-command-vector [cm-vector]
  (reduce (fn [acc command-map]
            (if (contains? -cm-type-instruction-defaults (:type (cm/command-data command-map)))
              acc (conj acc command-map)))
    [] cm-vector))

(defn- validate-registry
  "Validates:
   - All specs are valid according to CommandMapSpec
   - No duplicate :type values
   
   Returns {:valid? true} or {:valid? false :errors [...]}"
  [command-specs]
  (let [empty-command-spec-list-errors (when (empty? command-specs)
                                         [{:type :empty-command-specs
                                           :message "Registry is empty"}])
        validation-errors (reduce (fn [errors spec]
                                    (if-let [error (:error (cm/validate-command-spec spec))]
                                      (conj errors {:type :invalid-spec
                                                    :command-map-spec/type (:type spec)
                                                    :message error})
                                      errors))
                            []
                            command-specs)
        duplicates (find-duplicate-types command-specs)
        duplicate-errors (map (fn [dup-type]
                                {:type :duplicate-type
                                 :command-map-spec/type dup-type
                                 :message (str "Duplicate command type: " dup-type)})
                           duplicates)
        all-errors (concat
                     validation-errors
                     duplicate-errors
                     empty-command-spec-list-errors)]
    (if (empty? all-errors)
      {:valid? true}
      {:valid? false :errors all-errors})))

(defn built?
  "Returns true if the given value is a properly built registry."
  [registry]
  (and (vector? registry)
       (:registry/validated (meta registry))))

(defn build
  "Builds a command registry from a sequence of command specifications.

   Validates all specs and returns a registry that can be used with execute.
   The registry is marked with metadata to enable caching of compilation results.

   Args:
     command-spec-list - A sequence of command specifications

   Returns:
     A validated registry vector with metadata for caching or throws an error"
  [command-spec-list]
  {:pre [(vector? command-spec-list)]}
  (let [specs (with-meta command-spec-list
                {:registry/validated true
                 :registry/hash (hash command-spec-list)})
        validation (validate-registry specs)
        specs-with-instruction-structure-commands (attach-instruction-commands specs)]
    (if (:valid? validation)
      specs-with-instruction-structure-commands
      (throw (ex-info "Invalid registry specification"
               {:errors (:errors validation)
                :registry specs})))))

