(ns commando.core-perf-test
  "Performance tests for Commando execute function.

   Run these tests individually in the REPL for detailed performance analysis.

   Performance tests were run with following setup:
   Model Name:            MacBook Pro
   Model Identifier:      Mac14,10
   Processor Name:        Apple M2 Pro
   Number of Processors:  1
   Total Number of Cores: 12 (8 performance and 4 efficiency)
   L2 Cache (per Core):   4 MB
   Memory:                16 GB


   Basic performance tests:
   (perf-simple-command)      ; Single command execution
   (perf-dependency-chain)    ; 5-step dependency chain
   (perf-medium-parallel)     ; 50 independent commands
   (perf-large-parallel)      ; 200 independent commands

   Complex scenarios:
   (perf-mixed-workflow)      ; Multi-command workflow with dependencies
   (perf-dependent-chain)      ; 25-level dependency chain
   (perf-wide-fanout)         ; 1->100 fan-out pattern
   (perf-deep-nested-instruction) ; 7 levels deep with 50+ cross-level references

   Edge cases:
   (perf-empty-instruction)   ; Empty instruction overhead

   Comparative tests:
   (perf-registry-vs-compiled) ; Direct vs pre-compiled performance

   Run all benchmarks:
   (run-all-benchmarks)       ; Execute complete performance suite"
  (:require
   [commando.commands.builtin :as cmds-builtin]
   [commando.core             :as commando]
   [criterium.core            :as cc]))

;;TODO include test with a really big registry, so like there are 50 command specs

;; Test command definitions
(def test-add-id-command
  {:type :test/add-id
   :recognize-fn #(and (map? %) (contains? % :test/add-id))
   :apply (fn [_instruction _command-path-obj command-map] (assoc command-map :id :test-id))
   :dependencies {:mode :all-inside}})

;; Performance test functions
(defn perf-simple-command
  []
  ;; === Simple single command execution ===
  ;;  Evaluation count : 34368 in 6 samples of 5728 calls.
  ;;  Execution time mean : 18.710971 µs
  ;;  Execution time std-deviation : 317.972351 ns
  ;;  Execution time lower quantile : 18.283290 µs ( 2.5%)
  ;;  Execution time upper quantile : 19.018313 µs (97.5%)
  ;;  Overhead used : 1.648411 ns
  (let [registry [cmds-builtin/command-from-spec test-add-id-command]
        instruction {"cmd" {:test/add-id "test"}}]
    ;; Verify correctness first
    (assert (= :ok (:status (commando/execute registry instruction))))
    (assert (contains? (get-in (commando/execute registry instruction) [:instruction "cmd"]) :id))
    (println "\n=== Simple single command execution ===")
    (cc/quick-bench (commando/execute registry instruction))))

(defn perf-dependency-chain
  "Benchmark 5-step dependency chain."
  []
  ;; === 5-step dependency chain ===
  ;; Evaluation count : 10368 in 6 samples of 1728 calls.
  ;; Execution time mean : 62.820517 µs
  ;; Execution time std-deviation : 1.072545 µs
  ;; Execution time lower quantile : 61.709531 µs ( 2.5%)
  ;; Execution time upper quantile : 63.890134 µs (97.5%)
  ;; Overhead used : 1.648411 ns
  (let [registry [cmds-builtin/command-from-spec test-add-id-command]
        instruction {"step1" {:test/add-id "first"}
                     "step2" {:commando/from ["step1"]}
                     "step3" {:commando/from ["step2"]}
                     "step4" {:commando/from ["step3"]}
                     "step5" {:commando/from ["step4"]}}]
    (let [result (commando/execute registry instruction)]
      (assert (= :ok (:status result)))
      (assert (every? #(contains? (get-in result [:instruction %]) :id) ["step1" "step2" "step3" "step4" "step5"])))
    (println "\n=== 5-step dependency chain ===")
    (cc/quick-bench (commando/execute registry instruction))))

(defn perf-medium-parallel
  "Benchmark 50 independent commands."
  []
  ;; === 50 independent commands ===
  ;; Evaluation count : 132 in 6 samples of 22 calls.
  ;; Execution time mean : 5.021323 ms
  ;; Execution time std-deviation : 201.747532 µs
  ;; Execution time lower quantile : 4.761436 ms ( 2.5%)
  ;; Execution time upper quantile : 5.239934 ms (97.5%)
  ;; Overhead used : 1.648411 ns
  (let [registry [cmds-builtin/command-from-spec test-add-id-command]
        instruction (into {}
                          (for [i (range 50)]
                            [i {:test/add-id (str "value-" i)}]))]
    (let [result (commando/execute registry instruction)]
      (assert (= :ok (:status result)))
      (assert (= 50 (count (filter #(contains? % :id) (vals (:instruction result)))))))
    (println "\n=== 50 independent commands ===")
    (cc/quick-bench (commando/execute registry instruction))))

(defn perf-large-parallel
  "Benchmark 200 independent commands."
  []
  ;; === 200 independent commands ===
  ;; Evaluation count : 12 in 6 samples of 2 calls.
  ;; Execution time mean : 81.060446 ms
  ;; Execution time std-deviation : 3.257158 ms
  ;; Execution time lower quantile : 77.974832 ms ( 2.5%)
  ;; Execution time upper quantile : 86.378269 ms (97.5%)
  ;; Overhead used : 1.648411 ns
  (let [registry [cmds-builtin/command-from-spec test-add-id-command]
        instruction (into {}
                          (for [i (range 200)]
                            [i {:test/add-id (str "value-" i)}]))]
    (let [result (commando/execute registry instruction)]
      (assert (= :ok (:status result)))
      (assert (= 200 (count (filter #(contains? % :id) (vals (:instruction result)))))))
    (println "\n=== 200 independent commands ===")
    (cc/quick-bench (commando/execute registry instruction))))

(defn perf-mixed-workflow
  "Benchmark complex mixed workflow with different command types"
  []
  ;; === Complex mixed workflow ===
  ;; Evaluation count : 2472 in 6 samples of 412 calls.
  ;; Execution time mean : 274.588157 µs
  ;; Execution time std-deviation : 32.080465 µs
  ;; Execution time lower quantile : 246.402883 µs ( 2.5%)
  ;; Execution time upper quantile : 323.434512 µs (97.5%)
  ;; Overhead used : 1.648411 ns
  (let [registry [cmds-builtin/command-from-spec test-add-id-command cmds-builtin/command-fn-spec]
        instruction {"source" 100
                     "doubled" {:commando/fn *
                                :args [{:commando/from ["source"]} 2]}
                     "tripled" {:commando/fn *
                                :args [{:commando/from ["source"]} 3]}
                     "sum" {:commando/fn +
                            :args [{:commando/from ["doubled"]} {:commando/from ["tripled"]}]}
                     "metadata" {:test/add-id "workflow"}
                     "result" {:commando/from ["sum"]}}]
    (let [result (commando/execute registry instruction)]
      (assert (= :ok (:status result)))
      (assert (= 500 (get-in result [:instruction "sum"])))
      (assert (= 500 (get-in result [:instruction "result"])))
      (assert (contains? (get-in result [:instruction "metadata"]) :id)))
    (println "\n=== Complex mixed workflow ===")
    (cc/quick-bench (commando/execute registry instruction))))

(defn perf-dependent-chain
  "Benchmark 25 dependent commands chain"
  []
  ;; === 25 dependency chain ===
  ;; Evaluation count : 1278 in 6 samples of 213 calls.
  ;; Execution time mean : 509.752558 µs
  ;; Execution time std-deviation : 14.089436 µs
  ;; Execution time lower quantile : 489.605357 µs ( 2.5%)
  ;; Execution time upper quantile : 524.969061 µs (97.5%)
  ;; Overhead used : 1.648411 ns
  (let [registry [cmds-builtin/command-from-spec test-add-id-command]
        instruction
        (reduce (fn [inst i]
                  (if (= i 0) (assoc inst i {:test/add-id (str "base-" i)}) (assoc inst i {:commando/from [(dec i)]})))
                {}
                (range 25))]
    (let [result (commando/execute registry instruction)]
      (assert (= :ok (:status result)))
      (assert (every? #(contains? (get-in result [:instruction %]) :id) (range 25))))
    (println "\n=== 25 dependency chain ===")
    (cc/quick-bench (commando/execute registry instruction))))

(defn perf-wide-fanout
  "Benchmark wide fan-out (1->100 dependencies)"
  []
  ;; === Wide fan-out (1->100) ===
  ;; Evaluation count : 120 in 6 samples of 20 calls.
  ;; Execution time mean : 5.575331 ms
  ;; Execution time std-deviation : 307.267615 µs
  ;; Execution time lower quantile : 5.198288 ms ( 2.5%)
  ;; Execution time upper quantile : 6.052582 ms (97.5%)
  ;; Overhead used : 1.648411 ns
  (let [registry [cmds-builtin/command-from-spec test-add-id-command]
        base-instruction {"base" {:test/add-id "shared"}}
        instruction (into base-instruction
                          (for [i (range 100)]
                            [i {:commando/from ["base"]}]))]
    (let [result (commando/execute registry instruction)]
      (assert (= :ok (:status result)))
      (assert (contains? (get-in result [:instruction "base"]) :id))
      (assert (every? #(contains? (get-in result [:instruction %]) :id) (range 100))))
    (println "\n=== Wide fan-out (1->100) ===")
    (cc/quick-bench (commando/execute registry instruction))))


(defn perf-empty-instruction
  "Benchmark empty instruction overhead"
  []
  ;; === Empty instruction ===
  ;; Evaluation count : 59592 in 6 samples of 9932 calls.
  ;; Execution time mean : 10.729503 µs
  ;; Execution time std-deviation : 418.557691 ns
  ;; Execution time lower quantile : 10.167363 µs ( 2.5%)
  ;; Execution time upper quantile : 11.199093 µs (97.5%)
  ;; Overhead used : 1.648411 ns
  (let [registry [cmds-builtin/command-from-spec test-add-id-command]]
    (assert (= :ok (:status (commando/execute registry {}))))
    (assert (= {} (:instruction (commando/execute registry {}))))
    (println "\n=== Empty instruction ===")
    (cc/quick-bench (commando/execute registry {}))))

(defn perf-deep-nested-instruction
  "Benchmark deeply nested instruction map with 50+ command references across multiple levels"
  []
  ;; === Deep nested instruction (7 levels, 50+ commands) ===
  ;; Evaluation count : 234 in 6 samples of 39 calls.
  ;; Execution time mean : 2.613528 ms
  ;; Execution time std-deviation : 74.361807 µs
  ;; Execution time lower quantile : 2.510160 ms ( 2.5%)
  ;; Execution time upper quantile : 2.699545 ms (97.5%)
  ;; Overhead used : 1.648411 ns
  (let [registry [cmds-builtin/command-from-spec test-add-id-command]
        instruction {"root1" {:test/add-id "root-cmd-1"}
                     "root2" {:test/add-id "root-cmd-2"}
                     "root3" {:commando/from ["root1"]}
                     "level1"
                     {"l1-cmd1" {:test/add-id "level1-base"}
                      "l1-cmd2" {:commando/from ["root1"]}
                      "l1-cmd3" {:commando/from ["root2"]}
                      "l1-cmd4" {:commando/from ["level1" "l1-cmd1"]}
                      "level2"
                      {"l2-cmd1" {:test/add-id "level2-base"}
                       "l2-cmd2" {:commando/from ["level1" "l1-cmd1"]}
                       "l2-cmd3" {:commando/from ["root1"]}
                       "l2-cmd4" {:commando/from ["level1" "l1-cmd2"]}
                       "l2-cmd5" {:commando/from ["level1" "level2" "l2-cmd1"]}
                       "level3"
                       {"l3-cmd1" {:test/add-id "level3-base"}
                        "l3-cmd2" {:commando/from ["level1" "level2" "l2-cmd1"]}
                        "l3-cmd3" {:commando/from ["root2"]}
                        "l3-cmd4" {:commando/from ["level1" "l1-cmd1"]}
                        "l3-cmd5" {:commando/from ["level1" "level2" "l2-cmd2"]}
                        "l3-cmd6" {:commando/from ["level1" "level2" "level3" "l3-cmd1"]}
                        "level4"
                        {"l4-cmd1" {:test/add-id "level4-base"}
                         "l4-cmd2" {:commando/from ["level1" "level2" "level3" "l3-cmd1"]}
                         "l4-cmd3" {:commando/from ["level1" "l1-cmd1"]}
                         "l4-cmd4" {:commando/from ["root1"]}
                         "l4-cmd5" {:commando/from ["level1" "level2" "l2-cmd1"]}
                         "l4-cmd6" {:commando/from ["level1" "level2" "level3" "l3-cmd2"]}
                         "l4-cmd7" {:commando/from ["level1" "level2" "level3" "level4" "l4-cmd1"]}
                         "level5"
                         {"l5-cmd1" {:test/add-id "level5-base"}
                          "l5-cmd2" {:commando/from ["level1" "level2" "level3" "level4" "l4-cmd1"]}
                          "l5-cmd3" {:commando/from ["root2"]}
                          "l5-cmd4" {:commando/from ["level1" "level2" "l2-cmd1"]}
                          "l5-cmd5" {:commando/from ["level1" "level2" "level3" "l3-cmd1"]}
                          "l5-cmd6" {:commando/from ["level1" "level2" "level3" "level4" "l4-cmd2"]}
                          "l5-cmd7" {:commando/from ["level1" "level2" "level3" "level4" "level5" "l5-cmd1"]}
                          "l5-cmd8" {:commando/from ["level1" "l1-cmd3"]}
                          "level6"
                          {"l6-cmd1" {:test/add-id "level6-base"}
                           "l6-cmd2" {:commando/from ["level1" "level2" "level3" "level4" "level5" "l5-cmd1"]}
                           "l6-cmd3" {:commando/from ["root1"]}
                           "l6-cmd4" {:commando/from ["level1" "level2" "level3" "l3-cmd1"]}
                           "l6-cmd5" {:commando/from ["level1" "level2" "level3" "level4" "l4-cmd1"]}
                           "l6-cmd6" {:commando/from ["level1" "level2" "level3" "level4" "level5" "l5-cmd2"]}
                           "l6-cmd7" {:commando/from ["level1" "level2" "level3" "level4" "level5" "level6" "l6-cmd1"]}
                           "l6-cmd8" {:commando/from ["level1" "l1-cmd2"]}
                           "l6-cmd9" {:commando/from ["level1" "level2" "l2-cmd3"]}
                           "level7"
                           {"l7-cmd1" {:test/add-id "level7-base"}
                            "l7-cmd2" {:commando/from ["level1" "level2" "level3" "level4" "level5" "level6" "l6-cmd1"]}
                            "l7-cmd3" {:commando/from ["root2"]}
                            "l7-cmd4" {:commando/from ["level1" "level2" "level3" "level4" "l4-cmd1"]}
                            "l7-cmd5" {:commando/from ["level1" "level2" "level3" "level4" "level5" "l5-cmd1"]}
                            "l7-cmd6" {:commando/from ["level1" "level2" "level3" "level4" "level5" "level6" "l6-cmd2"]}
                            "l7-cmd7" {:commando/from
                                       ["level1" "level2" "level3" "level4" "level5" "level6" "level7" "l7-cmd1"]}
                            "l7-cmd8" {:commando/from ["level1" "level2" "l2-cmd4"]}
                            "l7-cmd9" {:commando/from ["level1" "level2" "level3" "l3-cmd3"]}
                            "l7-cmd10" {:commando/from ["level1" "level2" "level3" "level4" "level5" "l5-cmd3"]}}}}}}}}}
        result (commando/execute registry instruction)]
    (assert (= :ok (:status result)))
    ;; Verify commands at various levels have been processed
    (assert (contains? (get-in result [:instruction "root1"]) :id))
    (assert (contains? (get-in result [:instruction "level1" "l1-cmd1"]) :id))
    (assert (contains? (get-in result [:instruction "level1" "level2" "l2-cmd1"]) :id))
    (assert (contains? (get-in result [:instruction "level1" "level2" "level3" "l3-cmd1"]) :id))
    (assert (contains? (get-in result [:instruction "level1" "level2" "level3" "level4" "l4-cmd1"]) :id))
    (assert (contains? (get-in result [:instruction "level1" "level2" "level3" "level4" "level5" "l5-cmd1"]) :id))
    (assert (contains? (get-in result [:instruction "level1" "level2" "level3" "level4" "level5" "level6" "l6-cmd1"])
                       :id))
    (assert (contains? (get-in result
                               [:instruction "level1" "level2" "level3" "level4" "level5" "level6" "level7" "l7-cmd1"])
                       :id))
    ;; Verify cross-level dependencies work
    (assert (contains? (get-in result
                               [:instruction "level1" "level2" "level3" "level4" "level5" "level6" "level7" "l7-cmd2"])
                       :id))
    (assert (contains? (get-in result
                               [:instruction "level1" "level2" "level3" "level4" "level5" "level6" "level7" "l7-cmd10"])
                       :id))
    (println "\n=== Deep nested instruction (7 levels, 50+ commands) ===")
    (cc/quick-bench (commando/execute registry instruction))))

(defn perf-registry-vs-compiled
  "Compare direct registry vs pre-compiled performance"
  []
  ;; === Direct registry execution ===
  ;; Baseline performance using registry directly
  ;; Evaluation count : 16590 in 6 samples of 2765 calls.
  ;; Execution time mean : 41.007615 µs
  ;; Execution time std-deviation : 2.673693 µs
  ;; Execution time lower quantile : 39.111703 µs ( 2.5%)
  ;; Execution time upper quantile : 45.405028 µs (97.5%)
  ;; Overhead used : 1.648411 ns
  ;;
  ;; === Pre-compiled execution ===
  ;; Performance using pre-compiled version
  ;; Evaluation count : 173178 in 6 samples of 28863 calls.
  ;; Execution time mean : 3.671263 µs
  ;; Execution time std-deviation : 121.707808 ns
  ;; Execution time lower quantile : 3.486865 µs ( 2.5%)
  ;; Execution time upper quantile : 3.806872 µs (97.5%)
  ;; Overhead used : 1.648411 ns
  (let [registry [cmds-builtin/command-from-spec test-add-id-command]
        instruction {"step1" {:test/add-id "first"}
                     "step2" {:commando/from ["step1"]}
                     "step3" {:commando/from ["step2"]}}
        compiler (commando/build-compiler registry instruction)]
    (assert (= (:instruction (commando/execute registry instruction))
               (:instruction (commando/execute compiler instruction))))
    (println "\n=== Direct registry execution ===")
    (println "Baseline performance using registry directly")
    (cc/quick-bench (commando/execute registry instruction))
    (println "\n=== Pre-compiled execution ===")
    (println "Performance using pre-compiled version")
    (cc/quick-bench (commando/execute compiler instruction))))

(defn run-all-benchmarks
  "Run complete performance benchmark suite"
  []
  (println "=================================================================")
  (println "COMMANDO PERFORMANCE BENCHMARK SUITE")
  (println "=================================================================")
  (println "Hardware specs will affect absolute timings.")
  (println "Focus on relative performance and regression detection.")
  (println "=================================================================")
  (perf-simple-command)
  (perf-dependency-chain)
  (perf-medium-parallel)
  (perf-large-parallel)
  (perf-mixed-workflow)
  (perf-empty-instruction)
  (perf-wide-fanout)
  (perf-dependent-chain)
  (perf-deep-nested-instruction)
  (perf-registry-vs-compiled)
  (println "\n=================================================================")
  (println "PERFORMANCE BENCHMARK SUITE COMPLETE")
  (println "================================================================="))

(defn run-quick-benchmarks
  "Run essential benchmarks for quick performance check"
  []
  (println "=== QUICK PERFORMANCE CHECK ===")
  (perf-simple-command)
  (perf-dependency-chain)
  (perf-medium-parallel)
  (perf-mixed-workflow)
  (perf-deep-nested-instruction)
  (println "=== QUICK CHECK COMPLETE ==="))

(comment
  ;; REPL usage examples:
  ;; Run individual benchmarks
  (perf-simple-command)
  (perf-dependency-chain)
  (perf-medium-parallel)
  ;; Run quick performance check
  (run-quick-benchmarks)
  ;; Run complete benchmark suite (takes longer)
  (run-all-benchmarks)
  ;; Compare specific scenarios
  (perf-registry-vs-compiled)
  ;; Test edge cases
  (perf-empty-instruction)
  (perf-dependent-chain)
  (perf-wide-fanout)
  (perf-deep-nested-instruction))
