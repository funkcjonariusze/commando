(ns commando.impl.dependency
  (:require
   [commando.impl.command-map :as cm]
   [commando.impl.utils       :as utils]))

(defn- build-path-trie
  "Builds a trie from a list of CommandMapPath objects for efficient path-based lookups."
  [cm-list]
  (reduce
    (fn [trie cmd]
      (assoc-in trie (conj (cm/command-path cmd) ::command) cmd))
    {}
    cm-list))

(defn- get-all-nested-commands
  "Lazily traverses a trie.
   Returns a lazy sequence of all command objects found."
  [trie]
  (->> (tree-seq map? (fn [node] (vals (dissoc node ::command))) trie)
       (keep ::command)))

(defmulti find-command-dependencies
  "Finds command dependencies based on dependency-type.

  Returns a set of CommandMapPath objects that must execute before the given command.

  Modes:
  - :all-inside - depends on all commands inside the Command:
     if it Map - the values, if it Vector - elements of vectors
  - :all-inside-recur - depends on all commands nested within this command's path
  - :point - depends on command(s) at a specific path, defined by :point-key
      setting key. :point collect only one depedency - the only one it refering.
  - :point-and-all-inside-recur - joined approach of :point and :all-inside-recur.
      collect dependencies for path it pointing along with nested dependecies under
      the pointed item. First tries to find a command at the exact target path. If not found,
      walks up the path hierarchy to find parent commands that will create/modify
      the target path.
  - :none - no dependencies (not implemented, returns empty set by default)"
  (fn [_command-path-obj _instruction _path-trie dependency-type] dependency-type))

(defmethod find-command-dependencies :default
  [_command-path-obj _instruction _path-trie type]
  (throw (ex-info (str utils/exception-message-header "Undefined dependency mode: " type)
                  {:message (str utils/exception-message-header "Undefined dependency mode: " type)
                   :dependency-mode type})))

(defmethod find-command-dependencies :all-inside
  [command-path-obj _instruction path-trie _type]
  (let [command-path (cm/command-path command-path-obj)
        sub-trie (get-in path-trie command-path)]
    (->> (vals (dissoc sub-trie ::command))
         (keep ::command)
         set)))

(defmethod find-command-dependencies :all-inside-recur
  [command-path-obj _instruction path-trie _type]
  (let [command-path (cm/command-path command-path-obj)
        sub-trie (get-in path-trie command-path)]
    (->> (get-all-nested-commands sub-trie)
         (remove #(= % command-path-obj))
         set)))

(defn resolve-relative-path
  "Resolves path segments with relative navigation (../ and ./) against a base path."
  [base-path segments]
  (let [{:keys [relative path]} (reduce (fn [acc segment]
                                          (let [{:keys [relative path]} acc]
                                            (cond
                                              (= segment "../") {:relative
                                                                 (if relative (butlast relative) (butlast base-path))
                                                                 :path path}
                                              (= segment "./") {:relative (if relative relative base-path)
                                                                :path path}
                                              :else {:relative relative
                                                     :path (conj path segment)})))
                                  {:relative nil
                                   :path []}
                                  segments)]
    (if relative (concat relative path) path)))

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
    (->> pointed-path
      (resolve-relative-path command-path)
      vec)))

(defmethod find-command-dependencies :point
  [command-path-obj instruction path-trie _type]
  (let [target-path (point-target-path instruction command-path-obj)]
    (if-let [point-command (get-in path-trie (conj target-path ::command))]
      #{point-command}
      (throw-point-error command-path-obj target-path instruction))))

(defn- point-find-parent-command
  "Walks up the path hierarchy to find the first parent command that exists in the trie."
  [path-trie target-path]
  (loop [current-path target-path]
    (when (seq current-path)
      (if-let [cmd (get-in path-trie (conj current-path ::command))]
        cmd
        (recur (butlast current-path))))))

(defmethod find-command-dependencies :point-and-all-inside-recur
  [command-path-obj instruction path-trie _type]
  (let [target-path (point-target-path instruction command-path-obj)
        sub-trie (get-in path-trie target-path)
        commands-at-target (set (get-all-nested-commands sub-trie))
        parent-command (point-find-parent-command path-trie target-path)]
    (cond
      (not-empty commands-at-target) commands-at-target
      parent-command #{parent-command}
      (path-exists-in-instruction? instruction target-path) #{}
      :else (throw-point-error command-path-obj target-path instruction))))

(defmethod find-command-dependencies :none [_command-path-obj _instruction _path-trie _type] #{})

(defn build-dependency-graph
  "Builds the dependency map for all commands in `cm-list`.
   Returns a map from CommandMapPath objects to their dependency sets."
  [instruction cm-list]
  (let [path-trie (build-path-trie cm-list)]
    (reduce (fn [dependency-acc command-path-obj]
              (let [dependency-mode (get-in (cm/command-data command-path-obj) [:dependencies :mode])]
                (assoc dependency-acc
                  command-path-obj
                  (find-command-dependencies command-path-obj instruction path-trie dependency-mode))))
            {}
            cm-list)))
