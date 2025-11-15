(ns commando.impl.executing
  (:require
   [commando.impl.command-map :as cm]))

(defn ^:private execute-single-command
  "Execute a single command and update the instruction at the given path.
   Returns updated instruction. Throws on execution failure."
  [instruction command-path-obj]
  (let [command-spec (cm/command-data command-path-obj)
        apply-fn (:apply command-spec)
        command-path (cm/command-path command-path-obj)]
    (if (empty? command-path)
      ;; For those case while whole Instruction is only One command
      ;; then the Command by the [] path should be replaced after apply-fn
      (let [result (apply-fn instruction command-path-obj instruction)]
        result)
      (let [current-value (get-in instruction command-path)
            result (apply-fn instruction command-path-obj current-value)]
        (assoc-in instruction command-path result)))))

(defn execute-commands
  "Execute commands in order, stopping on first failure.
   Returns [updated-instruction error-info] where error-info is nil on success."
  [instruction commands]
  (loop [current-instruction instruction
         remaining-commands commands]
    (if (empty? remaining-commands)
      [current-instruction nil]
      (let [command (first remaining-commands)
            execution-result (try (execute-single-command current-instruction command)
                                  (catch #?(:clj Exception
                                            :cljs :default)
                                      e
                                    {:error {:command-path (cm/command-path command)
                                             :command-type (:type (cm/command-data command))
                                             :original-error e}}))]
        (if (:error execution-result)
          [current-instruction (:error execution-result)]
          (recur execution-result (rest remaining-commands)))))))

