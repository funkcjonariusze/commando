(ns commando.core-test
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [commando.commands.builtin :as cmds-builtin]
   [commando.core             :as commando]
   [commando.impl.command-map :as cm]
   [malli.core                :as malli]
   [commando.impl.registry    :as commando-registry]))

(def test-add-id-command
  {:type :test/add-id
   :recognize-fn #(and (map? %) (contains? % :test/add-id))
   :apply (fn [_instruction _command-path-obj command-map] (assoc command-map :id :test-id))
   :dependencies {:mode :all-inside}})

(def registry
  (->
    [cmds-builtin/command-from-spec
     test-add-id-command]
    (commando-registry/build)
    (commando-registry/enrich-runtime-registry)))

;; -- Failing commands --

(def failing-commands
  {:bad-cmd {:type :test/bad
             :recognize-fn #(and (map? %) (contains? % :will-fail))
             :apply (fn [_ _ _] (throw (ex-info "Intentional failure" {})))
             :dependencies {:mode :all-inside}}
   :timeout-cmd {:type :test/failing
                 :recognize-fn #(and (map? %) (contains? % :fail))
                 :apply (fn [_ _ _] (throw (ex-info "Command failed" {})))
                 :dependencies {:mode :none}}})

(def nil-handler-command
  {:type :test/nil-handler
   :recognize-fn #(and (map? %) (contains? % :handle-nil))
   :apply (fn [_instruction _command-path _command-map] nil)
   :dependencies {:mode :none}})

;; -- Status maps for execute-commands! --

(def fail-status-map
  {:status :failed
   :instruction {"test" 1}
   :registry registry
   :warnings ["Previous failure"]})

(def empty-execution-map
  {:status :ok
   :instruction {"val" 42}
   :registry registry
   :internal/cm-running-order []})

(def basic-command-execution-map
  {:status :ok
   :instruction {"val" 10
                 "cmd" {:test/add-id "data"}}
   :registry (commando/registry-create registry)
   :internal/cm-running-order [(cm/->CommandMapPath ["cmd"] test-add-id-command)]})

(def from-command
  {:status :ok
   :instruction {"source" 42
                 "ref" {:commando/from ["source"]}}
   :registry (commando/registry-create [cmds-builtin/command-from-spec])
   :internal/cm-running-order [(cm/->CommandMapPath ["ref"] cmds-builtin/command-from-spec)]})

(def fn-command
  {:status :ok
   :instruction {"calc" {:commando/fn +
                         :args [1 2 3]}}
   :registry (commando/registry-create [cmds-builtin/command-fn-spec])
   :internal/cm-running-order [(cm/->CommandMapPath ["calc"] cmds-builtin/command-fn-spec)]})

(def apply-command
  {:status :ok
   :instruction {"transform" {:commando/apply {"data" 10}
                              :=> [:fn #(get % "data")]}}
   :registry (commando/registry-create [cmds-builtin/command-apply-spec])
   :internal/cm-running-order [(cm/->CommandMapPath ["transform"] cmds-builtin/command-apply-spec)]})

(def nil-handler-execution-map
  {:status :ok
   :instruction {"nil-handler" {:handle-nil nil}}
   :registry (commando/registry-create [nil-handler-command])
   :internal/cm-running-order [(cm/->CommandMapPath ["nil-handler"] nil-handler-command)]})

(def bad-command-execution-map
  {:status :ok
   :instruction {"bad" {:will-fail true}}
   :registry (commando/registry-create [(:bad-cmd failing-commands)])
   :internal/cm-running-order [(cm/->CommandMapPath ["bad"] (:bad-cmd failing-commands))]})

(def midway-fail-execution-map
  {:status :ok
   :instruction {"good" {:test/add-id "works"}
                 "bad" {:will-fail true}
                 "never" {:test/add-id "should-not-execute"}}
   :registry (commando/registry-create [test-add-id-command
                                        (:bad-cmd failing-commands)])
   :internal/cm-running-order [(cm/->CommandMapPath ["good"] test-add-id-command)
                               (cm/->CommandMapPath ["bad"] (:bad-cmd failing-commands))
                               (cm/->CommandMapPath ["never"] test-add-id-command)]})

(def deep-nested-execution-map
  {:status :ok
   :instruction {"level1" {"level2" {"level3" {"deep" {:test/add-id "deep-value"}}}}}
   :registry (commando/registry-create [test-add-id-command])
   :internal/cm-running-order [(cm/->CommandMapPath ["level1" "level2" "level3" "deep"] test-add-id-command)]})

(def large-commands-execution-map
  (let [commands (mapv #(cm/->CommandMapPath [%] test-add-id-command) (range 20))
        instruction (into {} (map #(vector % {:test/add-id (str "value-" %)}) (range 20)))]
    {:status :ok
     :instruction instruction
     :registry (commando/registry-create [test-add-id-command])
     :internal/cm-running-order commands}))

(deftest execute-commands!-test
  (testing "Status handling"
    (is (commando/failed? (#'commando/execute-commands! fail-status-map)) "Failed status is preserved")
    (is (not-empty (:warnings (#'commando/execute-commands! fail-status-map))) "Warnings are preserved")
    (is (commando/failed? (#'commando/execute-commands! midway-fail-execution-map))
        "Failed status when command fails midway")
    (is (commando/ok? (#'commando/execute-commands! basic-command-execution-map))
        "Success status when commands execute successfully")
    (is (commando/ok? (#'commando/execute-commands! empty-execution-map)) "Success status when no commands to execute")
    (is (commando/ok? (#'commando/execute-commands! from-command)) "Success status for from command")
    (is (commando/ok? (#'commando/execute-commands! fn-command)) "Success status for fn command")
    (is (commando/ok? (#'commando/execute-commands! apply-command)) "Success status for apply command")
    (is (commando/ok? (#'commando/execute-commands! nil-handler-execution-map)) "Nil returning command is successful"))
  (testing "Basic functionality"
    (is (= 10 (get-in (#'commando/execute-commands! basic-command-execution-map) [:instruction "val"]))
        "Non-command values preserved")
    (is (= :test-id (get-in (#'commando/execute-commands! basic-command-execution-map) [:instruction "cmd" :id]))
        "Command executed")
    (is (= 42 (get-in (#'commando/execute-commands! from-command) [:instruction "ref"]))
        "commando/from executes correctly")
    (is (= 6 (get-in (#'commando/execute-commands! fn-command) [:instruction "calc"]))
        "commando/fn executes function with args")
    (is (= 10 (get-in (#'commando/execute-commands! apply-command) [:instruction "transform"]))
        "commando/apply transforms value")
    (is (= :test-id (get-in (#'commando/execute-commands! midway-fail-execution-map) [:instruction "good" :id]))
        "When failure happens - partial results are returned")
    (is (= {:test/add-id "should-not-execute"}
           (get-in (#'commando/execute-commands! midway-fail-execution-map) [:instruction "never"]))
        "After one command fails next ones do not execute")
    (is (contains? (get-in (#'commando/execute-commands! deep-nested-execution-map)
                           [:instruction "level1" "level2" "level3" "deep"])
                   :id)
        "Deep nested command executes")
    (is (not-empty (:errors (#'commando/execute-commands! bad-command-execution-map)))
        "Errors populated for failing command")
    (is (every? #(contains? (get-in (#'commando/execute-commands! large-commands-execution-map) [:instruction %]) :id)
                (range 20))
        "All commands execute successfully"))
  (testing "Edge cases"
    (is (= {"val" 42} (:instruction (#'commando/execute-commands! empty-execution-map)))
        "Empty running order preserves instruction values")
    (is (= nil (get-in (#'commando/execute-commands! nil-handler-execution-map) [:instruction "nil-handler"]))
        "Nil values handled correctly")))

;; -- Integration: execute pipeline --

(def custom-op-cmd
  {:type :OP
   :recognize-fn #(and (map? %) (contains? % :OP))
   :validate-params-fn (fn [m] (malli/validate [:map [:OP [:enum :SUMM :MULTIPLY]] [:ARGS [:+ :any]]] m))
   :apply (fn [_instruction _command-path-obj m]
            (case (:OP m)
              :SUMM (apply + (:ARGS m))
              :MULTIPLY (apply * (:ARGS m))))
   :dependencies {:mode :all-inside}})

(def custom-arg-cmd
  {:type :ARG
   :recognize-fn #(and (map? %) (contains? % :ARG))
   :validate-params-fn (fn [m] (malli/validate [:map [:ARG [:+ :any]]] m))
   :apply (fn [instruction _command-path-obj m] (get-in instruction (:ARG m)))
   :dependencies {:mode :point
                  :point-key [:ARG]}})

(deftest execute-test
  (testing "Status"
    (is (commando/ok? (commando/execute [cmds-builtin/command-from-spec]
                        {"source" 42 "ref" {:commando/from ["source"]}}))
        "Status :ok when successful")
    (is (commando/ok? (commando/execute [cmds-builtin/command-from-spec test-add-id-command]
                        {"data" 123 "info" "text"}))
        "Instruction with no commands succeeds")
    (is (commando/ok? (commando/execute [cmds-builtin/command-from-spec test-add-id-command] {}))
        "Empty instruction succeeds")
    (is (commando/failed? (commando/execute [] {"cmd" {:test/add-id "value"}}))
        "Empty registry fails")
    (is (commando/failed? (commando/execute [cmds-builtin/command-from-spec]
                            {"a" {:commando/from ["b"]} "b" {:commando/from ["a"]}}))
        "Circular dependencies detected")
    (is (commando/failed? (commando/execute [cmds-builtin/command-from-spec]
                            {"cmd" {:commando/from ["nonexistent"]}}))
        "Non-existent path reference fails"))
  (testing "Custom commands"
    (is (= {"A" 5 "B" 10 "result-multiply-1" 20 "result-multiply-2" 20 "result" 41}
           (:instruction (commando/execute [custom-op-cmd custom-arg-cmd]
                           {"A" 5
                            "B" 10
                            "result-multiply-1" {:OP :MULTIPLY :ARGS [{:ARG ["A"]} 4]}
                            "result-multiply-2" {:OP :MULTIPLY :ARGS [{:ARG ["B"]} 2]}
                            "result" {:OP :SUMM :ARGS [{:ARG ["result-multiply-1"]} {:ARG ["result-multiply-2"]} 1]}})))
        "Flat custom instruction with OP/ARG commands")
    (is (= {"A" 5 "B" 10 "result" 41}
           (:instruction (commando/execute [custom-op-cmd custom-arg-cmd]
                           {"A" 5
                            "B" 10
                            "result" {:OP :SUMM
                                      :ARGS [{:OP :MULTIPLY :ARGS [{:ARG ["A"]} 4]}
                                             {:OP :MULTIPLY :ARGS [{:ARG ["B"]} 2]}
                                             1]}})))
        "Nested custom instruction"))
  (testing "Top-level Vector Instruction"
    (let [result (commando/execute [cmds-builtin/command-from-spec]
                   [{:value 10}
                    {:commando/from [0 :value] :=> [:fn inc]}
                    {:commando/from [1] :=> [:fn (partial * 2)]}])]
      (is (commando/ok? result) "Vector instruction is acceptable")
      (is (= [{:value 10} 11 22] (:instruction result))))))

