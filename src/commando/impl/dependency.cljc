(ns commando.impl.dependency
  (:require
   [commando.impl.command-map :as cm]
   [commando.impl.utils       :as utils]))

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
  (fn [_command-path-obj _instruction _commands dependency-type] dependency-type))

(defmethod find-command-dependencies :default
  [_command-path-obj _instruction _commands type]
  (throw (ex-info (str utils/exception-message-header "Undefined dependency mode: " type)
                  {:message (str utils/exception-message-header "Undefined dependency mode: " type)
                   :dependency-mode type})))

(defmethod find-command-dependencies :all-inside
  [command-path-obj _instruction commands _type]
  (let [command-path-obj-length (count (cm/command-path command-path-obj))]
   (->> (disj commands command-path-obj)
     (filter #(and
                (= (dec (count (cm/command-path %))) command-path-obj-length)
                (cm/start-with? % command-path-obj)))
     (set))))

(defmethod find-command-dependencies :all-inside-recur
  [command-path-obj _instruction commands _type]
  (->> (disj commands command-path-obj)
    (filter #(cm/start-with? % command-path-obj))
    (set)))

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

(defn find-commands-at-target-path
  "Finds all commands at or nested within the target path."
  [commands target-path]
  (->> commands
       (filter #(cm/vector-starts-with? (cm/command-path %) target-path))
       set))

(defn path-exists-in-instruction?
  "Checks if a path exists in the instruction map."
  [instruction path]
  (not= ::not-found (get-in instruction path ::not-found)))

(defn throw-point-error
  "Throws a standardized error for missing point dependencies."
  [command-path-obj target-path instruction]
  (let [deps-config (:dependencies (cm/command-data command-path-obj))
        error-msg (str utils/exception-message-header
                       "Point dependency failed: key '" (:point-key deps-config)
                       "' references non-existent path " target-path)]
    (throw (ex-info error-msg
                    {:message error-msg
                     :path (cm/command-path command-path-obj)
                     :command (get-in instruction (cm/command-path command-path-obj))}))))

(defn point-target-path
  "Returns the target path for a :point dependency, resolving relative navigation."
  [instruction command-path-obj]
  (let [point-key (get-in (cm/command-data command-path-obj) [:dependencies :point-key])
        command-path (cm/command-path command-path-obj)
        command-map (get-in instruction command-path)
        pointed-path (get command-map point-key)]
    (->> pointed-path
      (resolve-relative-path command-path)
      vec)))

(defmethod find-command-dependencies :point
  [command-path-obj instruction commands _type]
  (let [target-path (point-target-path instruction command-path-obj)
        commands-at-target (cm/->CommandMapPath target-path {})]
    (if-let [point-command (first (filter #(= % commands-at-target) commands))]
      #{point-command}
      (throw-point-error command-path-obj target-path instruction))))

(defn point-find-parent-command
  "Walks up the path hierarchy to find the first parent command that exists in commands."
  [commands target-path]
  (loop [current-path target-path]
    (cond
      (empty? current-path) nil
      (contains? commands (cm/->CommandMapPath current-path {}))
      (first (filter #(= (cm/command-path %) current-path) commands))
      :else (recur (butlast current-path)))))

(defmethod find-command-dependencies :point-and-all-inside-recur
  [command-path-obj instruction commands _type]
  (let [target-path (point-target-path instruction command-path-obj)
        commands-at-target (find-commands-at-target-path commands target-path)
        parent-command (point-find-parent-command commands target-path)]
    (cond
      (not-empty commands-at-target) commands-at-target
      parent-command #{parent-command}
      (path-exists-in-instruction? instruction target-path) #{}
      :else (throw-point-error command-path-obj target-path instruction))))

(defmethod find-command-dependencies :none [_command-path-obj _instruction _commands _type] #{})

(defn build-dependency-graph
  "Builds the dependency map for all commands in `cm-list`.
   Returns a map from CommandMapPath objects to their dependency sets."
  [instruction cm-list]
  (let [command-set (set cm-list)]
    (reduce (fn [dependency-acc command-path-obj]
              (let [dependency-mode (get-in (cm/command-data command-path-obj) [:dependencies :mode])]
               (assoc dependency-acc
                 command-path-obj
                 (find-command-dependencies command-path-obj instruction command-set dependency-mode)
                 )))
            {}
            cm-list)))
