(ns commando.impl.finding-commands
  (:require
   [commando.impl.command-map :as cm]
   [commando.impl.pathtrie :as pathtrie]
   [commando.impl.utils :as utils]))

(defn ^:private enqueue-coll-children!
  "Enqueues child paths for regular collections directly into the transient queue.
   Avoids intermediate vector allocation compared to mapv approach."
  [queue value current-path]
  (cond
    (map? value)
    (reduce-kv (fn [q k _] (conj! q (conj current-path k))) queue value)

    (coll? value)
    (let [c (count value)]
      (loop [i 0 q queue]
        (if (= i c) q
          (recur (inc i) (conj! q (conj current-path i))))))

    :else queue))

(defmulti ^:private enqueue-command-children!
  "Enqueues child paths that should be traversed for a command based on its dependency mode.
   Takes a transient queue vector and returns it with children added."
  (fn [_queue command-spec _value _current-path] (get-in command-spec [:dependencies :mode])))

(defmethod enqueue-command-children! :default [queue _command-spec _value _current-path] queue)

(defmethod enqueue-command-children! :all-inside [queue _command-spec value current-path]
  (enqueue-coll-children! queue value current-path))

(defmethod enqueue-command-children! :quote
  [queue command-spec value current-path]
  (let [{:keys [finding-commands-unquote-keys
                finding-commands-skip-keys]} (:dependencies command-spec)
        marked?    (fn [m ks] (and (map? m) (some #(contains? m %) ks)))
        marked-key (fn [m ks] (some #(when (contains? m %) %) ks))]
    (loop [stack [[current-path value]] q queue]
      (if-let [[path node] (peek stack)]
        (cond
          (and (not= path current-path) (marked? node finding-commands-unquote-keys))
          (recur (pop stack) (conj! q (conj path (marked-key node finding-commands-unquote-keys))))
          (and (not= path current-path) (marked? node finding-commands-skip-keys))
          (recur (pop stack) q)
          (map? node)
          (recur (into (pop stack) (map (fn [[k v]] [(conj path k) v])) node) q)
          (coll? node)
          (recur (into (pop stack) (map-indexed (fn [i v] [(conj path i) v])) node) q)
          :else (recur (pop stack) q))
        q))))

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
  [command-spec-vector value path]
  (some (fn [command-spec]
          (when (command? command-spec value)
            (let [value-valid-return (command-valid? command-spec value)]
              (if (true? value-valid-return) command-spec
                  (throw
                    (ex-info
                      (str
                        "Failed while validating params for " (:type command-spec) ". Check ':validate-params-fn' property for corresponding command with value it was evaluated on.")
                      {:command-type (:type command-spec)
                       :reason (when value-valid-return value-valid-return)
                       :path path
                       :value value}))))))
        command-spec-vector))

(defn find-commands
  "Traverses the instruction tree (BFS) and collects all commands defined by the registry.
   Returns {:commands #{...} :trie {...}} — the command set and path-trie built in the same pass.

   Options:
   Optimizations:
   - Index-based transient queue: O(N) instead of O(N²) from subvec+into copying
   - Transient found-commands set: O(N) set allocations saved
   - Direct enqueue: no intermediate mapv vectors for child-path generation
   - Transient trie root: N root-level HAMT copies avoided during bulk construction"
  ([instruction command-registry]
   (find-commands instruction command-registry nil))
  ([instruction {:keys [registry-runtime] :as _command-registry} _opts]
   (loop [queue (transient [[]])
            idx 0
            found-commands (transient #{})
            trie (transient {})]
       (if (= idx (count queue))
         {:commands (persistent! found-commands) :trie (persistent! trie)}
         (let [current-path (nth queue idx)
               current-value (get-in instruction current-path)]
           (if-let [command-spec (instruction-command-spec registry-runtime current-value current-path)]
             (let [command (cm/command-map-path current-path command-spec)]
               (recur (enqueue-command-children! queue command-spec current-value current-path)
                      (inc idx)
                      (conj! found-commands command)
                      (pathtrie/trie-insert-command! trie command)))
             (recur (enqueue-coll-children! queue current-value current-path)
                    (inc idx)
                    found-commands
                    trie)))))))

