(ns commando.impl.dependency
  (:require
   [clojure.string            :as str]
   [commando.impl.command-map :as cm]
   [commando.impl.utils       :as utils]))

(defmulti find-command-dependencies
  "Finds command dependencies based on dependency-type.

  Returns a set of CommandMapPath objects that must execute before the given command.

  Modes:
  - :all-inside - depends on all commands inside the Command:
     if it Map - the values, if it Vector - elements of vectors
  - :point - depends on command(s) at a specific path, defined by :point-key
      setting key. :point collect only one depedency - the only one it refering.
  - :none - no dependencies (not implemented, returns empty set by default)
  - :quote - internal dependency visible only under unquote-keys"
  (fn [_command-path-obj _instruction _path-trie dependency-type] dependency-type))

;; -- Default --

(defmethod find-command-dependencies :default
  [_command-path-obj _instruction _path-trie type]
  (throw (ex-info (str utils/exception-message-header "Undefined dependency mode: " type)
           {:message (str utils/exception-message-header "Undefined dependency mode: " type)
            :dependency-mode type})))

;; -- None --

(defmethod find-command-dependencies :none [_command-path-obj _instruction _path-trie _type] #{})

;; -- All Inside --

(defmethod find-command-dependencies :quote
  [command-path-obj _instruction path-trie _type]
  (let [command-path (cm/command-path command-path-obj)
        sub-trie (get-in path-trie command-path)]
    (letfn [(collect [acc node]
              (reduce-kv (fn [a k v]
                           (cond
                             ;; `=` (not `identical?`): keyword identity is unreliable in cljs,
                             ;; which would silently drop quote->hole dependency edges.
                             (= k :commando.impl.pathtrie/command) (conj a v)
                             (map? v) (collect a v)
                             :else a))
                         acc node))]
      (collect #{} (dissoc sub-trie :commando.impl.pathtrie/command)))))

(defmethod find-command-dependencies :all-inside
  [command-path-obj _instruction path-trie _type]
  ;; Direct reduce-kv instead of dissoc+vals+keep+set chain.
  ;; Avoids: 1 dissoc (new map), 1 vals (lazy seq), 1 keep (lazy seq), 1 set (materialization).
  (let [command-path (cm/command-path command-path-obj)
        sub-trie (get-in path-trie command-path)]
    (reduce-kv (fn [acc k v]
                 (if (identical? k :commando.impl.pathtrie/command)
                   acc
                   (if-let [cmd (:commando.impl.pathtrie/command v)]
                     (conj acc cmd)
                     acc)))
      #{} sub-trie)))

;; -- Point --

(defn- find-anchor-path
  "Walks UP from current-path looking for the nearest ancestor map
   that has key \"__anchor\" or :__anchor equal to anchor-name.
   Returns the path vector to that ancestor, or nil if not found."
  [instruction current-path anchor-name]
  (loop [path (vec current-path)]
    (let [node (get-in instruction path)]
      (if (and (map? node)
            (= anchor-name (or (get node "__anchor")
                             (get node :__anchor))))
        path
        (when (seq path)
          (recur (pop path)))))))

(defn resolve-relative-path
  "Resolves path segments with relative navigation against a base path.
  Returns nil if an @anchor segment cannot be resolved.

  Supported segment types:
    \"../\"        - go up one level from current position
    \"./\"         - stay at current level (noop for relative base)
    \"@anchor\"    - jump to nearest ancestor with matching __anchor name
                    (requires instruction to be passed as first argument)
    any other      - descend into that key"
  [instruction base-path segments]
  (loop [remaining (seq segments)
         relative nil
         path []]
    (if-not remaining
      (if relative (into relative path) path)
      (let [segment (first remaining)
            current-base (or relative base-path)]
        (cond
          (= segment "../")
          (recur (next remaining) (vec (butlast current-base)) path)

          (= segment "./")
          (recur (next remaining) (vec current-base) path)

          (and instruction
            (string? segment)
            (str/starts-with? segment "@"))
          (let [anchor-name (subs segment 1)
                anchor-path (find-anchor-path instruction (butlast current-base) anchor-name)]
            (when anchor-path
              (recur (next remaining) anchor-path path)))

          :else
          (recur (next remaining) relative (conj path segment)))))))

(defn path-exists-in-instruction?
  "Checks if a path exists in the instruction map."
  [instruction path]
  (not= ::not-found (get-in instruction path ::not-found)))

(defn throw-point-error
  "Throws a standardized error for missing point dependencies."
  [command-path-obj target-path instruction]
  (let [deps-config (:dependencies (cm/command-data command-path-obj))
        command-map (get-in instruction (cm/command-path command-path-obj))
        point-key-config (:point-key deps-config)
        actual-key (if (sequential? point-key-config)
                     (reduce (fn [_ point-key] (when (contains? command-map point-key) (reduced point-key)))
                             (first point-key-config)
                             point-key-config)
                     point-key-config)
        error-msg (str utils/exception-message-header
                       "Point dependency failed: key '" actual-key
                       "' references non-existent path " target-path)]
    (throw (ex-info error-msg
                    {:message error-msg
                     :path (cm/command-path command-path-obj)
                     :command command-map}))))

(defn point-target-path
  "Returns the target path for a :point dependency, resolving relative navigation."
  [instruction command-path-obj]
  (let [point-key-seq (get-in (cm/command-data command-path-obj) [:dependencies :point-key])
        command-path (cm/command-path command-path-obj)
        command-map (get-in instruction command-path)
        pointed-path (reduce (fn [_ point-key]
                               (when-let [pointed-path (get command-map point-key)]
                                 (reduced pointed-path)))
                       nil
                       point-key-seq)]
    (or (resolve-relative-path instruction command-path pointed-path)
        (throw-point-error command-path-obj pointed-path instruction))))

(defmethod find-command-dependencies :point
  [command-path-obj instruction path-trie _type]
  (let [target-path (point-target-path instruction command-path-obj)]
    (if-let [point-command (get-in path-trie (conj target-path :commando.impl.pathtrie/command))]
      #{point-command}
      (throw-point-error command-path-obj target-path instruction))))

;; Dependency

(defn build-dependency-graph
  "Builds forward dependency graph using a pre-built path-trie.
   Returns {CommandMapPath -> #{deps}}."
  [instruction cm-list path-trie]
  (persistent!
    (reduce (fn [fwd command-path-obj]
              (let [dep-mode (get-in (cm/command-data command-path-obj) [:dependencies :mode])
                    deps (find-command-dependencies command-path-obj instruction path-trie dep-mode)]
                (assoc! fwd command-path-obj deps)))
      (transient {})
      cm-list)))

