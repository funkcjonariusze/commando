(ns commando.impl.executing
  (:require
   [commando.impl.command-map :as cm]))

;; == Driver Multimethod ==================================

(defn ^:private resolve-command-driver
  "Parses :=> value into [driver-keyword driver-params].
   Accepts:
     :keyword                   -> [:keyword nil]
     \"string\"                 -> [:keyword nil]
     [:get :name]               -> [:get (:name)]
     [:get-in [:address :city]] -> [:get-in ([:address :city])]
     [\"get\" \"name\"]         -> [:get (\"name\")]

   Pipeline (first element is vector):
     [[:get :name] :uppercase]  -> [:pipe raw]

   Priority: :=> in command-data > :default (:get-in)."
  [command-data _command-spec]
  (let [raw (or (get command-data :=>) (get command-data "=>"))]
    (cond
      (nil? raw)     [:identity nil]
      (keyword? raw) [raw nil]
      (string? raw)  [(keyword raw) nil]
      (vector? raw)  (let [drv (first raw)]
                       (if (or (vector? drv) (sequential? drv))
                         [:pipe raw]
                         [(if (string? drv) (keyword drv) drv)
                          (rest raw)]))
      :else          [:identity nil])))

(defmulti command-driver
  "Post-processing driver for command results.
   Dispatched on driver-name keyword (first argument).

   Arguments:
     driver-name      - keyword, dispatch value
     driver-params    - seq of params from :=> vector (nil when no params)
     applied-result   - value returned by :apply
     command-data     - original command map (before :apply ran)
     instruction      - full instruction map
     command-path-obj - CommandMapPath object"
  (fn [driver-name _driver-params _applied-result _command-data _instruction _command-path-obj]
    driver-name))

;; == Execution Engine =====================================

(defn ^:private execute-single-command
  "Execute a single command and update the instruction at the given path.
   After :apply produces a raw result, the driver post-processes it.
   Returns updated instruction. Throws on execution failure."
  [instruction command-path-obj]
  (let [command-spec          (cm/command-data command-path-obj)
        apply-fn              (:apply command-spec)
        command-path          (cm/command-path command-path-obj)
        root?                 (empty? command-path)
        command-data          (if root? instruction (get-in instruction command-path))
        applied-result        (apply-fn instruction command-path-obj (if (map? command-data)
                                                                         (dissoc command-data :=> "=>")
                                                                         command-data))
        [drv-name drv-params] (resolve-command-driver command-data command-spec)
        result                (command-driver
                                drv-name drv-params applied-result
                                command-data instruction command-path-obj)]
    (if root? result (assoc-in instruction command-path result))))

(defn execute-commands
  "Execute commands in order, stopping on first failure.
   Returns [updated-instruction error-info cm-results].
   Transient results map: avoids N persistent map copies during execution loop.
   Each command adds one entry — transient turns N×assoc into N×assoc! (zero HAMT copies)."
  [instruction commands]
  (let [cmd-count (count commands)]
    (loop [current-instruction instruction
           idx 0
           results (transient {})]
      (if (= idx cmd-count)
        [current-instruction nil (persistent! results)]
        (let [command      (nth commands idx)
              command-path (cm/command-path command)
              root?        (empty? command-path)
              execution-result (try (execute-single-command current-instruction command)
                                    (catch #?(:clj Exception
                                              :cljs :default)
                                        e
                                      {:error {:command-path command-path
                                               :command-type (:type (cm/command-data command))
                                               :original-error e}}))]
          (if (:error execution-result)
            [current-instruction (:error execution-result) (persistent! results)]
            (let [cmd-result (if root? execution-result (get-in execution-result command-path))]
              (recur execution-result (inc idx) (assoc! results command cmd-result)))))))))
