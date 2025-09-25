(ns commando.core-test
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [commando.commands.builtin :as cmds-builtin]
   [commando.core             :as commando]
   [commando.impl.command-map :as cm]
   [malli.core                :as malli]))

;; -------
;; Helpers
;; -------

(defn status-map-contains-error?
  [status-map error]
  (if (commando/failed? status-map)
    (let [error-lookup-fn (cond
                            (string? error) (fn [e] (= (:message e) error))
                            (map? error) (fn [e] (= e error))
                            (fn? error) error
                            :else nil)]
      (if error-lookup-fn (some? (first (filter error-lookup-fn (:errors status-map)))) false))
    false))

;; -----
;; Tests
;; -----

(def test-add-id-command
  {:type :test/add-id
   :recognize-fn #(and (map? %) (contains? % :test/add-id))
   :apply (fn [_instruction _command-path-obj command-map] (assoc command-map :id :test-id))
   :dependencies {:mode :all-inside}})

(def registry [cmds-builtin/command-from-spec test-add-id-command])

(def fail-validation-command
  {:type :fail-validation
   :recognize-fn #(and (map? %) (contains? % :should-fail))
   :validate-params-fn (fn [_] false)
   :apply identity
   :dependencies {:mode :all-inside}})

(def fail-recognize-command
  {:type :fail-recognize
   :recognize-fn (fn [_] (throw (ex-info "Recognition failed" {})))
   :apply identity
   :dependencies {:mode :all-inside}})

(deftest find-commands
  (testing "Edge cases"
    (is (= []
           (:internal/cm-list (#'commando/find-commands
                               {:status :ok
                                :instruction {}
                                :registry registry})))
        "Empty instruction map gives empty command list")
    (is (= []
           (:internal/cm-list (#'commando/find-commands
                               {:status :ok
                                :instruction {:some-val {:a 2}
                                              :some-other 3
                                              :my-value :is-here
                                              :i {:am {:deep :nested}}}
                                :registry registry})))
        "Instruction with values but no commands return empty cm-list")
    (is (= []
           (:internal/cm-list (#'commando/find-commands
                               {:status :ok
                                :instruction {:cmd {:commando/from [:a]}
                                              :a 1}
                                :registry []})))
        "Empty registry finds nothing")
    (is (= 0
           (count (:internal/cm-list (#'commando/find-commands
                                      {:status :ok
                                       :instruction {:set #{:commando/from [:target]}
                                                     :list (list {:commando/from [:target]})
                                                     :target 42}
                                       :registry registry}))))
        "Does not traverse into sets or lists")
    (is (= 1
           (count (:internal/cm-list (#'commando/find-commands
                                      {:status :ok
                                       :instruction {:set #{:not-found}
                                                     :list (list :not-found)
                                                     :valid [{:commando/from [:target]}]
                                                     :target 42}
                                       :registry registry}))))
        "Finds commands from vectors while ignores sets/lists")
    (is (= [:valid 0]
           (cm/command-path (first (:internal/cm-list (#'commando/find-commands
                                                       {:status :ok
                                                        :instruction {:set #{:not-found}
                                                                      :list (list :not-found)
                                                                      :valid [{:commando/from [:target]}]
                                                                      :target 42}
                                                        :registry registry})))))
        "Correctly identifies vector-based command path")
    (is (= 0
           (count (:internal/cm-list (#'commando/find-commands
                                      {:status :ok
                                       :instruction {:a nil
                                                     :b {}
                                                     :c []}
                                       :registry registry}))))
        "Nil values and empty containers don't produce commands")
    (is (= 1
           (count (:internal/cm-list (#'commando/find-commands
                                      {:status :ok
                                       :instruction {:a nil
                                                     :b {}
                                                     :c []
                                                     :valid {:commando/from [:target]}
                                                     :target 42}
                                       :registry registry}))))
        "Finds valid commands despite presence of nil/empty values"))
  (testing "Basic cases"
    (is (= [(cm/->CommandMapPath [:d] cmds-builtin/command-from-spec)]
           (:internal/cm-list (#'commando/find-commands
                               {:status :ok
                                :instruction {:a 2
                                              :b {:c 5}
                                              :d {:commando/from [:a]}}
                                :registry registry})))
        "Find one command")
    (is (= [(cm/->CommandMapPath [] test-add-id-command)]
           (:internal/cm-list (#'commando/find-commands
                               {:status :ok
                                :instruction {:test/add-id 5}
                                :registry registry})))
        "Whole instruction map is a command")
    (is (= [(cm/->CommandMapPath [:d :first] cmds-builtin/command-from-spec)
            (cm/->CommandMapPath [:d :second] cmds-builtin/command-from-spec)]
           (:internal/cm-list (#'commando/find-commands
                               {:status :ok
                                :instruction {:a 2
                                              :b {:c 5}
                                              :d {:first {:commando/from [:a]}
                                                  :second {:commando/from [:b :c]}}}
                                :registry registry})))
        "Find two commands in nested map")
    (is (= [(cm/->CommandMapPath [:d 0] cmds-builtin/command-from-spec)
            (cm/->CommandMapPath [:d 2] cmds-builtin/command-from-spec)]
           (:internal/cm-list (#'commando/find-commands
                               {:status :ok
                                :instruction {:a 2
                                              :b {:c 5}
                                              :d [{:commando/from [:a]} :some {:commando/from [:b :c]}]}
                                :registry registry})))
        "Find two commands in vector")
    (let [result (:internal/cm-list (#'commando/find-commands
                                     {:status :ok
                                      :instruction {:goal {:test/add-id :fn
                                                           :ref {:commando/from [:other]}}
                                                    :other "value"}
                                      :registry registry}))]
      (is (some #(= (cm/command-path %) [:goal]) result) "Finds :test/add-id command at [:goal] path")
      (is (some #(= (cm/command-path %) [:goal :ref]) result) "Finds :commando/from command at [:goal :ref] path")
      (is (some #(= (:type (cm/command-data %)) :test/add-id) result) "Correctly identifies :test/add-id command type")
      (is (some #(= (:type (cm/command-data %)) :commando/from) result)
          "Correctly identifies :commando/from command type"))
    (let [deep-result (:internal/cm-list (#'commando/find-commands
                                          {:status :ok
                                           :instruction {:a {"some" {:c [:some {:commando/from [:target]}]}}
                                                         :target 42}
                                           :registry registry}))]
      (is (= 1 (count deep-result)) "Finds exactly one command in deeply nested structure")
      (is (= [:a "some" :c 1] (cm/command-path (first deep-result)))
          "Correctly identifies path 4 levels deep in different access structure, keyword/string/vector")))
  (testing "Self-referential commands"
    (let [result (:internal/cm-list (#'commando/find-commands
                                     {:status :ok
                                      :instruction {:a {:b {:ref {:commando/from [:a :b]}
                                                            :data 42}}
                                                    :target "value"}
                                      :registry registry}))]
      (is (= 1 (count result)) "Finds self-referential command")
      (is (= [:a :b :ref] (cm/command-path (first result))) "Correctly identifies path of self-referential command"))
    (let [result (:internal/cm-list (#'commando/find-commands
                                     {:status :ok
                                      :instruction {:parent {:child {:ref {:commando/from [:parent]}
                                                                     :value 10}
                                                             :data "test"}}
                                      :registry registry}))]
      (is (= 1 (count result)) "Finds command referencing parent path")
      (is (= [:parent :child :ref] (cm/command-path (first result)))
          "Correctly identifies path of parent-referential command"))
    (let [result (:internal/cm-list (#'commando/find-commands
                                     {:status :ok
                                      :instruction {:a {:b {:ref1 {:commando/from [:a :b :ref2]}
                                                            :ref2 {:commando/from [:a :b :ref1]}}}}
                                      :registry registry}))]
      (is (= 2 (count result)) "Finds mutually referential commands")
      (is (some #(= (cm/command-path %) [:a :b :ref1]) result) "Finds first mutually referential command")
      (is (some #(= (cm/command-path %) [:a :b :ref2]) result) "Finds second mutually referential command")))
  (testing "Status handling"
    (is (= :failed
           (:status (#'commando/find-commands
                     {:status :failed
                      :instruction {:cmd {:commando/from [:a]}}
                      :registry registry})))
        "Failed status is preserved")
    (is (= :failed
           (:status (#'commando/find-commands
                     {:status :ok
                      :instruction {:cmd {:should-fail true}
                                    :b 2
                                    :a {:commando/from [:b]}}
                      :registry [fail-validation-command]})))
        "When validation of params fails find-commands ends with :failed")
    (is (= :failed
           (:status (#'commando/find-commands
                     {:status :ok
                      :instruction {:cmd {:any "value"}}
                      :registry [fail-recognize-command]})))
        "When recognize function throws exception find-commands ends with :failed")
    (is (= :ok
           (:status (#'commando/find-commands
                     {:status :ok
                      :instruction {:goal {:test/add-id :fn
                                           :ref {:commando/from [:other]}}
                                    :other "value"}
                      :registry registry})))
        "Success status when everything goes well"))
  (testing "Path accuracy - handles different key types correctly"
    (let [mixed-keys-result (:internal/cm-list (#'commando/find-commands
                                                {:status :ok
                                                 :instruction {"string-key" {:commando/from [:a]}
                                                               :keyword-key {:commando/from [:a]}
                                                               42 {:commando/from [:a]}
                                                               :a 1}
                                                 :registry registry}))]
      (is (= 3 (count mixed-keys-result)) "Finds all commands with different key types")
      (is (some #(= (cm/command-path %) ["string-key"]) mixed-keys-result) "Correctly handles string keys in paths")
      (is (some #(= (cm/command-path %) [:keyword-key]) mixed-keys-result) "Correctly handles keyword keys in paths")
      (is (some #(= (cm/command-path %) [42]) mixed-keys-result) "Correctly handles numeric keys in paths"))))

; Test data for build-deps-tree
(def cmd1 (cm/->CommandMapPath [:goal1] test-add-id-command))
(def cmd2 (cm/->CommandMapPath [:goal2 :ref] cmds-builtin/command-from-spec))

(def parent-cmd (cm/->CommandMapPath [:parent] test-add-id-command))
(def child-cmd (cm/->CommandMapPath [:parent :child] test-add-id-command))

(def target-cmd (cm/->CommandMapPath [:target] test-add-id-command))
(def ref-cmd (cm/->CommandMapPath [:ref] cmds-builtin/command-from-spec))

(def failed-ref-cmd (cm/->CommandMapPath [:ref] cmds-builtin/command-from-spec))

; Test status maps
(def failed-status-map
  {:status :failed
   :instruction {}
   :registry registry
   :internal/cm-list []})
(def empty-ok-status-map
  {:status :ok
   :instruction {:a 1}
   :registry registry
   :internal/cm-list []})

(def deps-test-status-map
  {:status :ok
   :instruction {:goal1 {:test/add-id :fn}
                 :goal2 {:ref {:commando/from [:goal1 :test-id]}}}
   :registry registry
   :internal/cm-list [cmd1 cmd2]})

(def all-inside-status-map
  {:status :ok
   :instruction {:parent {:test/add-id :fn
                          :child {:test/add-id :fn}}}
   :registry registry
   :internal/cm-list [parent-cmd child-cmd]})

(def point-deps-status-map
  {:status :ok
   :instruction {:target {:test/add-id :fn}
                 :ref {:commando/from [:target]}}
   :registry registry
   :internal/cm-list [target-cmd ref-cmd]})

(def error-status-map
  {:status :ok
   :instruction {:ref {:commando/from [:nonexistent]}}
   :registry registry
   :internal/cm-list [failed-ref-cmd]})

(def chain-cmd-a (cm/->CommandMapPath [:a] cmds-builtin/command-from-spec))
(def chain-cmd-b (cm/->CommandMapPath [:b] cmds-builtin/command-from-spec))
(def chain-cmd-c (cm/->CommandMapPath [:c] test-add-id-command))

(def diamond-cmd-a (cm/->CommandMapPath [:a] cmds-builtin/command-from-spec))
(def diamond-cmd-b (cm/->CommandMapPath [:b] cmds-builtin/command-from-spec))
(def diamond-cmd-c (cm/->CommandMapPath [:c] cmds-builtin/command-from-spec))
(def diamond-cmd-d (cm/->CommandMapPath [:d] test-add-id-command))

(def circular-cmd-a (cm/->CommandMapPath [:a] cmds-builtin/command-from-spec))
(def circular-cmd-b (cm/->CommandMapPath [:b] cmds-builtin/command-from-spec))

(def hierarchy-parent (cm/->CommandMapPath [:root] test-add-id-command))
(def hierarchy-child1 (cm/->CommandMapPath [:root :child1] cmds-builtin/command-from-spec))
(def hierarchy-child2 (cm/->CommandMapPath [:root :child2] test-add-id-command))

(def deep-shallow (cm/->CommandMapPath [:deep :nested :cmd] cmds-builtin/command-from-spec))
(def shallow-target (cm/->CommandMapPath [:target] test-add-id-command))

(def multi-ref-cmd (cm/->CommandMapPath [:multi] cmds-builtin/command-from-spec))
(def ref-target1 (cm/->CommandMapPath [:target1] test-add-id-command))
(def ref-target2 (cm/->CommandMapPath [:target2] test-add-id-command))

(def sibling1 (cm/->CommandMapPath [:container :sib1] cmds-builtin/command-from-spec))
(def sibling2 (cm/->CommandMapPath [:container :sib2] test-add-id-command))

; Complex test status maps
(def chained-deps-map
  {:status :ok
   :instruction {:a {:commando/from [:b]}
                 :b {:commando/from [:c]}
                 :c {:test/add-id :fn}}
   :registry registry
   :internal/cm-list [chain-cmd-a chain-cmd-b chain-cmd-c]})

(def diamond-deps-map
  {:status :ok
   :instruction {:a {:commando/from [:b]}
                 :b {:commando/from [:d]}
                 :c {:commando/from [:d]}
                 :d {:test/add-id :fn}}
   :registry registry
   :internal/cm-list [diamond-cmd-a diamond-cmd-b diamond-cmd-c diamond-cmd-d]})

(def circular-deps-map
  {:status :ok
   :instruction {:a {:commando/from [:b]}
                 :b {:commando/from [:a]}}
   :registry registry
   :internal/cm-list [circular-cmd-a circular-cmd-b]})

(def hierarchy-deps-map
  {:status :ok
   :instruction {:root {:test/add-id :fn
                        :child1 {:commando/from [:root :child2]}
                        :child2 {:test/add-id :fn}}}
   :registry registry
   :internal/cm-list [hierarchy-parent hierarchy-child1 hierarchy-child2]})

(def deep-cross-ref-map
  {:status :ok
   :instruction {:deep {:nested {:cmd {:commando/from [:target]}}}
                 :target {:test/add-id :fn}}
   :registry registry
   :internal/cm-list [deep-shallow shallow-target]})

(def multi-point-deps-map
  {:status :ok
   :instruction {:multi {:commando/from [:target1]}
                 :target1 {:test/add-id :fn}
                 :target2 {:test/add-id :fn}}
   :registry registry
   :internal/cm-list [multi-ref-cmd ref-target1 ref-target2]})

(def sibling-deps-map
  {:status :ok
   :instruction {:container {:sib1 {:commando/from [:container :sib2]}
                             :sib2 {:test/add-id :fn}}}
   :registry registry
   :internal/cm-list [sibling1 sibling2]})

(defn cmd-by-path [path commands] (first (filter #(= (cm/command-path %) path) commands)))


(deftest build-deps-tree
  (testing "Status handling"
    (is (commando/failed? (#'commando/build-deps-tree failed-status-map)) "Failed status is preserved")
    (is (commando/failed? (#'commando/build-deps-tree error-status-map))
        "Returns failed status when point dependency fails")
    (is (commando/failed? (#'commando/build-deps-tree
                           {:status :ok
                            :instruction {:bad {:commando/from [:nonexistent :path]}}
                            :registry registry
                            :internal/cm-list [(cm/->CommandMapPath [:bad] cmds-builtin/command-from-spec)]}))
        "Returns failed status for non-existent path references")
    (is (commando/ok? (#'commando/build-deps-tree empty-ok-status-map)) "Success status with empty command list")
    (is (commando/failed? (#'commando/build-deps-tree deps-test-status-map))
        "Status failed cause :test-id unexist in dependency step"))
  (testing "Base case"
    (let [result (#'commando/build-deps-tree all-inside-status-map)
          deps (:internal/cm-dependency result)]
      (is (contains? result :internal/cm-dependency) "Contains dependency map")
      (is (and (contains? deps parent-cmd) (contains? deps child-cmd)) "Contains all commands")
      (is (contains? (get (:internal/cm-dependency (#'commando/build-deps-tree all-inside-status-map)) parent-cmd)
                     child-cmd)
          "Parent command depends on child command"))
    (is (contains? (get (:internal/cm-dependency (#'commando/build-deps-tree point-deps-status-map)) ref-cmd)
                   target-cmd)
        "Ref command depends on target command")
    (let [deps (:internal/cm-dependency (#'commando/build-deps-tree chained-deps-map))]
      (is (contains? (get deps chain-cmd-a) chain-cmd-b) "A depends on B")
      (is (contains? (get deps chain-cmd-b) chain-cmd-c) "B depends on C")
      (is (empty? (get deps chain-cmd-c)) "C has no dependencies"))
    (let [deps (:internal/cm-dependency (#'commando/build-deps-tree diamond-deps-map))]
      (is (contains? (get deps diamond-cmd-b) diamond-cmd-d) "B depends on D")
      (is (contains? (get deps diamond-cmd-c) diamond-cmd-d) "C depends on D")
      (is (empty? (get deps diamond-cmd-d)) "D has no dependencies"))
    (let [deps (:internal/cm-dependency (#'commando/build-deps-tree hierarchy-deps-map))]
      (is (contains? (get deps hierarchy-parent) hierarchy-child1) "Parent depends on child1 (all-inside)")
      (is (contains? (get deps hierarchy-parent) hierarchy-child2) "Parent depends on child2 (all-inside)")
      (is (contains? (get deps hierarchy-child1) hierarchy-child2) "Child1 depends on child2 (point ref)"))
    (let [deps (:internal/cm-dependency (#'commando/build-deps-tree deep-cross-ref-map))]
      (is (contains? (get deps deep-shallow) shallow-target) "Deep nested command depends on shallow target"))
    (let [deps (:internal/cm-dependency (#'commando/build-deps-tree multi-point-deps-map))]
      (is (contains? (get deps multi-ref-cmd) ref-target1) "Multi-ref command depends on target1"))
    (let [deps (:internal/cm-dependency (#'commando/build-deps-tree sibling-deps-map))]
      (is (contains? (get deps sibling1) sibling2) "Same-level sibling dependencies, sibling1 depends on sibling2")))
  (testing "Complex multi-level dependency resolution"
    (let [large-test-instruction
          {:config {:database {:test/add-id :database}
                    :cache {:test/add-id :cache}}
           :users {:fetch {:commando/from [:config :database]}
                   :validate {:commando/from [:users :fetch]}
                   :transform {:commando/from [:config :cache]}}
           :products {:load {:test/add-id :products
                             :items {:fetch {:commando/from [:config :database]}
                                     :enrich {:commando/from [:products :load :items :fetch]}}}
                      :cache {:commando/from [:products :load]}}
           :orders {:create {:commando/from [:users :validate]}
                    :prepare {:commando/from [:products :cache]}
                    :finalize {:test/add-id :finalize
                               :needs-create {:commando/from [:orders :create]}
                               :needs-prepare {:commando/from [:orders :prepare]}}
                    :process {:test/add-id :orders
                              :steps {:validate {:commando/from [:orders :create]}
                                      :payment {:commando/from [:orders :process :steps :validate]}
                                      :fulfill {:commando/from [:orders :process :steps :payment]}}}}
           :reports {:daily {:commando/from [:orders :process]}
                     :weekly [{:commando/from [:reports :daily]} {:commando/from [:users :transform]}]}}
          large-test-commands (:internal/cm-list (#'commando/find-commands
                                                  {:status :ok
                                                   :instruction large-test-instruction
                                                   :registry registry}))
          large-deps-status-map {:status :ok
                                 :instruction large-test-instruction
                                 :registry registry
                                 :internal/cm-list large-test-commands}
          result (#'commando/build-deps-tree large-deps-status-map)
          deps (:internal/cm-dependency result)
          config-db (cmd-by-path [:config :database] large-test-commands)
          config-cache (cmd-by-path [:config :cache] large-test-commands)
          users-fetch (cmd-by-path [:users :fetch] large-test-commands)
          users-validate (cmd-by-path [:users :validate] large-test-commands)
          users-transform (cmd-by-path [:users :transform] large-test-commands)
          products-load (cmd-by-path [:products :load] large-test-commands)
          products-items-fetch (cmd-by-path [:products :load :items :fetch] large-test-commands)
          products-items-enrich (cmd-by-path [:products :load :items :enrich] large-test-commands)
          products-cache (cmd-by-path [:products :cache] large-test-commands)
          orders-create (cmd-by-path [:orders :create] large-test-commands)
          orders-prepare (cmd-by-path [:orders :prepare] large-test-commands)
          orders-finalize (cmd-by-path [:orders :finalize] large-test-commands)
          orders-needs-create (cmd-by-path [:orders :finalize :needs-create] large-test-commands)
          orders-needs-prepare (cmd-by-path [:orders :finalize :needs-prepare] large-test-commands)
          orders-process (cmd-by-path [:orders :process] large-test-commands)
          orders-steps-validate (cmd-by-path [:orders :process :steps :validate] large-test-commands)
          orders-steps-payment (cmd-by-path [:orders :process :steps :payment] large-test-commands)
          orders-steps-fulfill (cmd-by-path [:orders :process :steps :fulfill] large-test-commands)
          reports-daily (cmd-by-path [:reports :daily] large-test-commands)
          reports-weekly (cmd-by-path [:reports :weekly 0] large-test-commands)
          reports-weekly2 (cmd-by-path [:reports :weekly 1] large-test-commands)]
      (is (commando/ok? result) "Successfully processes large dependency tree")
      (is (= 21 (count large-test-commands)) "Sanity input check: All 21 commands are present")
      (is (= 21 (count deps)) "Dependency map contains all 21 commands")
      (is (empty? (get deps config-db)) "config.database has no dependencies")
      (is (empty? (get deps config-cache)) "config.cache has no dependencies")
      (is (contains? (get deps users-fetch) config-db) "users.fetch depends on config.database")
      (is (contains? (get deps products-items-fetch) config-db) "products.load.items.fetch depends on config.database")
      (is (contains? (get deps users-validate) users-fetch) "users.validate depends on users.fetch")
      (is (contains? (get deps users-transform) config-cache) "users.transform depends on config.cache")
      (is (contains? (get deps products-items-enrich) products-items-fetch)
          "products.load.items.enrich depends on products.load.items.fetch")
      (is (contains? (get deps products-cache) products-load)
          "products.cache pointing at products.load has as a dependency it pointed")
      (is
       (= (get deps products-load) #{})
       "products.load has items.fetch (all-inside) child dep inside. But its still meen that products.load should not contain dependency, cause it depends only from internal structured values, and next this values has references to items.fetch.")
      (is (contains? (get deps orders-create) users-validate) "orders.create depends on users.validate")
      (is (contains? (get deps orders-prepare) products-cache) "orders.prepare depends on products.cache")
      (is (contains? (get deps orders-finalize) orders-needs-create)
          "orders.finalize depends on needs-create (all-inside) - multi-reference dependencies via all-inside pattern")
      (is (contains? (get deps orders-finalize) orders-needs-prepare)
          "orders.finalize depends on needs-prepare (all-inside) - multi-reference dependencies via all-inside pattern")
      (is (contains? (get deps orders-needs-create) orders-create)
          "orders.finalize.needs-create depends on orders.create")
      (is (contains? (get deps orders-needs-prepare) orders-prepare)
          "orders.finalize.needs-prepare depends on orders.prepare")
      (is (= (get deps orders-process) #{}) "orders.process hasn't dependency")
      (is (contains? (get deps orders-steps-validate) orders-create)
          "orders.process.steps.validate depends on orders.create")
      (is (contains? (get deps orders-steps-payment) orders-steps-validate)
          "orders.process.steps.payment depends on orders.process.steps.validate")
      (is (contains? (get deps orders-steps-fulfill) orders-steps-payment)
          "orders.process.steps.fulfill depends on orders.process.steps.payment")
      (is (contains? (get deps reports-daily) orders-process) "reports.daily depends on orders.process")
      (is (contains? (get deps reports-weekly) reports-daily) "reports.weekly depends on reports.daily")
      (is (contains? (get deps reports-weekly2) users-transform)
          "reports.weekly depends on users.transform (multi-path dependency)")))
  (testing "Circular dependencies"
    ;; TODO this will work here but to think about how it should be in
    ;; global exec test (especially after sort)
    (let [result (#'commando/build-deps-tree circular-deps-map)]
      (is (or (commando/ok? result) (commando/failed? result))
          "Handles circular dependencies (either succeeds or fails gracefully)")))
  (testing "Empty command list"
    (let [empty-map {:status :ok
                     :instruction {}
                     :registry registry
                     :internal/cm-list []}
          result (#'commando/build-deps-tree empty-map)]
      (is (commando/ok? result) "Handles empty command list successfully")
      (is (empty? (:internal/cm-dependency result)) "Dependency map is empty"))))

(deftest dependency-modes-test
  (testing ":all-inside mode"
    (let [goal2-cmd (cm/->CommandMapPath [:goal-2] test-add-id-command)
          goal2-someval-cmd (cm/->CommandMapPath [:goal-2 :some-val] test-add-id-command)
          test-status-map {:status :ok
                           :instruction {:goal-2 {:test/add-id :fn
                                                  :some-val {:test/add-id :nested}}}
                           :registry registry
                           :internal/cm-list [goal2-cmd goal2-someval-cmd]}
          result (#'commando/build-deps-tree test-status-map)
          deps (:internal/cm-dependency result)]
      (is (commando/ok? result) "Successfully processes :all-inside dependency")
      (is (contains? (get deps goal2-cmd) goal2-someval-cmd)
          "goal-2 command depends on goal-2.some-val command (nested inside)")))
  (testing ":point mode"
    (let [goal1-cmd (cm/->CommandMapPath [:goal-1] test-add-id-command)
          ref-cmd (cm/->CommandMapPath [:ref] cmds-builtin/command-from-spec)
          test-status-map {:status :ok
                           :instruction {:goal-1 {:test/add-id :fn}
                                         :ref {:commando/from [:goal-1]}}
                           :registry registry
                           :internal/cm-list [goal1-cmd ref-cmd]}
          result (#'commando/build-deps-tree test-status-map)
          deps (:internal/cm-dependency result)]
      (is (commando/ok? result) "Successfully processes :point dependency")
      (is (contains? (get deps ref-cmd) goal1-cmd)
          "ref command depends on goal-1 command (parent path dependency because goal-1 will create :id key)")))
  (testing ":none mode - no dependencies"
    (let [none-command {:type :test/none
                        :recognize-fn #(and (map? %) (contains? % :test/none))
                        :apply identity
                        :dependencies {:mode :none}}
          none-cmd (cm/->CommandMapPath [:standalone] none-command)
          test-status-map {:status :ok
                           :instruction {:standalone {:test/none :independent}}
                           :registry [none-command]
                           :internal/cm-list [none-cmd]}
          result (#'commando/build-deps-tree test-status-map)
          deps (:internal/cm-dependency result)]
      (is (commando/ok? result) "Successfully processes :none dependency")
      (is (empty? (get deps none-cmd)) "Command with :none mode has no dependencies"))))

(deftest sort-entities-by-deps
  (testing "Status handling"
    (is (commando/failed? (#'commando/sort-commands-by-deps failed-status-map)) "Failed status is preserved")
    (is (commando/ok? (#'commando/sort-commands-by-deps empty-ok-status-map))
        "Success status with empty dependency map"))
  (testing "Simple dependency chain ordering"
    (let [deps-map {:status :ok
                    :instruction {:a {:commando/from [:b]}
                                  :b {:commando/from [:c]}
                                  :c {:test/add-id :fn}}
                    :registry registry
                    :internal/cm-dependency {chain-cmd-a #{chain-cmd-b}
                                             chain-cmd-b #{chain-cmd-c}
                                             chain-cmd-c #{}}}
          result (#'commando/sort-commands-by-deps deps-map)
          order (:internal/cm-running-order result)]
      (is (commando/ok? result) "Successfully sorts linear dependency chain")
      (is (= 3 (count order)) "Returns all commands in order")
      (is (< (.indexOf order chain-cmd-c) (.indexOf order chain-cmd-b) (.indexOf order chain-cmd-a))
          "Commands ordered correctly: c before b before a")))
  (testing "Diamond dependency pattern"
    (let [deps-map {:status :ok
                    :instruction {:a {:commando/from [:b :c]}
                                  :b {:commando/from [:d]}
                                  :c {:commando/from [:d]}
                                  :d {:test/add-id :fn}}
                    :registry registry
                    :internal/cm-dependency {diamond-cmd-a #{diamond-cmd-b diamond-cmd-c}
                                             diamond-cmd-b #{diamond-cmd-d}
                                             diamond-cmd-c #{diamond-cmd-d}
                                             diamond-cmd-d #{}}}
          result (#'commando/sort-commands-by-deps deps-map)
          order (:internal/cm-running-order result)]
      (is (commando/ok? result) "Successfully sorts diamond dependency")
      (is (= 4 (count order)) "Returns all commands in order")
      (is (< (.indexOf order diamond-cmd-d) (.indexOf order diamond-cmd-b)) "D executes before B")
      (is (< (.indexOf order diamond-cmd-d) (.indexOf order diamond-cmd-c)) "D executes before C")
      (is (< (.indexOf order diamond-cmd-b) (.indexOf order diamond-cmd-a)) "B executes before A")
      (is (< (.indexOf order diamond-cmd-c) (.indexOf order diamond-cmd-a)) "C executes before A")))
  (testing "Circular dependency detection"
    (let [deps-map {:status :ok
                    :instruction {:a {:commando/from [:b]}
                                  :b {:commando/from [:a]}}
                    :registry registry
                    :internal/cm-dependency {circular-cmd-a #{circular-cmd-b}
                                             circular-cmd-b #{circular-cmd-a}}}
          result (#'commando/sort-commands-by-deps deps-map)]
      (is (commando/failed? result) "Detects circular dependency and returns failed status")
      (is (some #(re-find #"cyclic dependency" %) (map :message (:errors result)))
          "Error message mentions cyclic dependency"))))

(def instruction
  {:config {:database {}
            :cache {}}
   :z-users {:fetch {:commando/from [:config :database]}
             :validate {:commando/from [:z-users :fetch]}}
   :a-products {:commando/from [:z-users]}
   :m-orders {:create {:commando/from [:z-users :validate]}
              :prepare {:commando/from [:a-products :fetch]}
              :finalize {:needs-create {:commando/from [:m-orders :create]}
                         :needs-prepare {:commando/from [:m-orders :prepare]}}}
   :z-reports {:daily {:commando/from [:m-orders]}
               :weekly {:commando/from [:m-orders :finalize :needs-create]
                        := :create}}
   :a-analytics {:summary {:commando/from [:z-reports :weekly]}
                 :export {:commando/from [:a-analytics :summary]}}})

(def mutation-timestamp-execution-map
  {:status :ok
   :instruction {"timestamp" {:commando/mutation :time/current-dd-mm-yyyy-hh-mm-ss}}
   :registry (commando/create-registry [cmds-builtin/command-mutation-spec])
   :internal/cm-running-order [(cm/->CommandMapPath ["timestamp"] cmds-builtin/command-mutation-spec)]})

(def from-transformation-execution-map
  {:status :ok
   :instruction {"source" {:data 42
                           :extra "info"}
                 "transformed" {:commando/from ["source"]
                                := :data}}
   :registry (commando/create-registry [cmds-builtin/command-from-spec])
   :internal/cm-running-order [(cm/->CommandMapPath ["transformed"] cmds-builtin/command-from-spec)]})

(def apply-transformation-execution-map
  {:status :ok
   :instruction {"processed" {:commando/apply [1 2 3 4 5]
                              := #(apply + %)}}
   :registry (commando/create-registry [cmds-builtin/command-apply-spec])
   :internal/cm-running-order [(cm/->CommandMapPath ["processed"] cmds-builtin/command-apply-spec)]})

;; ================================
;; SHARED TEST DATA DEFINITIONS
;; ================================

(def empty-execution-map
  {:status :ok
   :instruction {"val" 42}
   :registry registry
   :internal/cm-running-order []})

(def basic-success-map
  {:status :ok
   :registry (commando/create-registry [test-add-id-command])
   :internal/cm-running-order []})

(def from-command
  {:status :ok
   :instruction {"source" 42
                 "ref" {:commando/from ["source"]}}
   :registry (commando/create-registry [cmds-builtin/command-from-spec])
   :internal/cm-running-order [(cm/->CommandMapPath ["ref"] cmds-builtin/command-from-spec)]})

(def fn-command
  {:status :ok
   :instruction {"calc" {:commando/fn +
                         :args [1 2 3]}}
   :registry (commando/create-registry [cmds-builtin/command-fn-spec])
   :internal/cm-running-order [(cm/->CommandMapPath ["calc"] cmds-builtin/command-fn-spec)]})

(def apply-command
  {:status :ok
   :instruction {"transform" {:commando/apply {"data" 10}
                              := #(get % "data")}}
   :registry (commando/create-registry [cmds-builtin/command-apply-spec])
   :internal/cm-running-order [(cm/->CommandMapPath ["transform"] cmds-builtin/command-apply-spec)]})

(def add-id-command-execution
  {:status :ok
   :instruction {"cmd" {:test/add-id "some-value"}}
   :registry (commando/create-registry [test-add-id-command])
   :internal/cm-running-order [(cm/->CommandMapPath ["cmd"] test-add-id-command)]})

(def dependency-scenarios
  {:all-inside {:instruction {"parent" {:test/add-id "parent-val"
                                        "child" {:test/add-id "child-val"}}}
                :commands [(cm/->CommandMapPath ["parent" "child"] test-add-id-command)
                           (cm/->CommandMapPath ["parent"] test-add-id-command)]}
   :point {:instruction {"target" {:test/add-id "target-val"}
                         "ref" {:commando/from ["target" :id]}}
           :commands [(cm/->CommandMapPath ["target"] test-add-id-command)
                      (cm/->CommandMapPath ["ref"] cmds-builtin/command-from-spec)]}
   :none {:instruction {"independent" {:test/none "value"}}
          :none-cmd {:type :test/none
                     :recognize-fn #(and (map? %) (contains? % :test/none))
                     :apply (fn [_instruction _command-path _command-map] "independent-result")
                     :dependencies {:mode :none}}}})

(def failing-commands
  {:bad-cmd {:type :test/bad
             :recognize-fn #(and (map? %) (contains? % :will-fail))
             :apply (fn [_ _ _] (throw (ex-info "Intentional failure" {})))
             :dependencies {:mode :all-inside}}
   :timeout-cmd {:type :test/failing
                 :recognize-fn #(and (map? %) (contains? % :fail))
                 :apply (fn [_ _ _] (throw (ex-info "Command failed" {})))
                 :dependencies {:mode :none}}})

(def fail-status-map
  {:status :failed
   :instruction {"test" 1}
   :registry registry
   :warnings ["Previous failure"]})

(def basic-command-execution-map
  {:status :ok
   :instruction {"val" 10
                 "cmd" {:test/add-id "data"}}
   :registry (commando/create-registry registry)
   :internal/cm-running-order [(cm/->CommandMapPath ["cmd"] test-add-id-command)]})

(def timeout-command-execution-map
  {:status :ok
   :instruction {"cmd" {:fail true}}
   :registry (commando/create-registry [(:timeout-cmd failing-commands)])
   :internal/cm-running-order [(cm/->CommandMapPath ["cmd"] (:timeout-cmd failing-commands))]})

(def bad-command-execution-map
  {:status :ok
   :instruction {"bad" {:will-fail true}}
   :registry (commando/create-registry [(:bad-cmd failing-commands)])
   :internal/cm-running-order [(cm/->CommandMapPath ["bad"] (:bad-cmd failing-commands))]})

(def midway-fail-execution-map
  {:status :ok
   :instruction {"good" {:test/add-id "works"}
                 "bad" {:will-fail true}
                 "never" {:test/add-id "should-not-execute"}}
   :registry (commando/create-registry [test-add-id-command (:bad-cmd failing-commands)])
   :internal/cm-running-order [(cm/->CommandMapPath ["good"] test-add-id-command)
                               (cm/->CommandMapPath ["bad"] (:bad-cmd failing-commands))
                               (cm/->CommandMapPath ["never"] test-add-id-command)]})

(def nil-handler-command
  {:type :test/nil-handler
   :recognize-fn #(and (map? %) (contains? % :handle-nil))
   :apply (fn [_instruction _command-path _command-map] nil)
   :dependencies {:mode :none}})

(def nil-handler-execution-map
  {:status :ok
   :instruction {"nil-handler" {:handle-nil nil}}
   :registry (commando/create-registry [nil-handler-command])
   :internal/cm-running-order [(cm/->CommandMapPath ["nil-handler"] nil-handler-command)]})

(def deep-nested-execution-map
  {:status :ok
   :instruction {"level1" {"level2" {"level3" {"deep" {:test/add-id "deep-value"}}}}}
   :registry (commando/create-registry [test-add-id-command])
   :internal/cm-running-order [(cm/->CommandMapPath ["level1" "level2" "level3" "deep"] test-add-id-command)]})

(def large-commands-execution-map
  (let [commands (mapv #(cm/->CommandMapPath [%] test-add-id-command) (range 20))
        instruction (into {} (map #(vector % {:test/add-id (str "value-" %)}) (range 20)))]
    {:status :ok
     :instruction instruction
     :registry (commando/create-registry [test-add-id-command])
     :internal/cm-running-order commands}))

(def full-registry-all
  [cmds-builtin/command-from-spec
   cmds-builtin/command-fn-spec
   cmds-builtin/command-apply-spec
   cmds-builtin/command-mutation-spec
   test-add-id-command])

(def from-instruction
  {"a" 10
   "b" {:commando/from ["a"]}})

(def fn-instruction
  {"calc" {:commando/fn +
           :args [1 2 3]}})

(def apply-instruction
  {"transform" {:commando/apply [1 2 3]
                := count}})

(def mixed-instruction
  {"source" 100
   "doubled" {:commando/fn *
              :args [{:commando/from ["source"]} 2]}
   "processed" {:commando/apply {:commando/from ["doubled"]}
                := str}
   "metadata" {:test/add-id "info"}})

(def transform-instruction
  {"data" {:nested {:value 42}}
   "extracted" {:commando/from ["data"]
                := #(get-in % [:nested :value])}})

;; Complex dependency scenario instructions
(def linear-chain-instruction
  {"step1" {:test/add-id "first"}
   "step2" {:commando/from ["step1"]}
   "step3" {:commando/from ["step2"]}
   "step4" {:commando/from ["step3"]}})

(def fan-out-instruction
  {"base" {:test/add-id "shared"}
   "branch1" {:commando/from ["base"]}
   "branch2" {:commando/from ["base"]}
   "branch3" {:commando/from ["base"]}})

(def diamond-instruction
  {"root" {:test/add-id "root"}
   "left" {:commando/from ["root"]}
   "right" {:commando/from ["root"]}
   "merge" {:commando/from ["left"]}})

(def hierarchical-instruction
  {"parent" {:test/add-id "parent"
             "child1" {:test/add-id "child1"}
             "child2" {:commando/from ["parent" "child1"]}}})

;; Error scenarios data and registries
(def error-registry [cmds-builtin/command-from-spec test-add-id-command (:timeout-cmd failing-commands)])

(def invalid-cmd
  {:type :test/invalid
   :recognize-fn #(and (map? %) (contains? % :invalid))
   :validate-params-fn (fn [_] false)
   :apply identity
   :dependencies {:mode :none}})

(def throwing-cmd
  {:type :test/throwing
   :recognize-fn (fn [_] (throw (ex-info "Recognition failed" {})))
   :apply identity
   :dependencies {:mode :none}})

(def failing-case-instruction
  {"good" {:test/add-id "works"}
   "bad" {:fail true}})

(def invalid-ref-instruction {"cmd" {:commando/from ["nonexistent"]}})

(def circular-instruction
  {"a" {:commando/from ["b"]}
   "b" {:commando/from ["a"]}})

(def empty-registry-instruction {"cmd" {:test/add-id "value"}})

(def invalid-validation-instruction {"cmd" {:invalid true}})

(def throwing-recognition-instruction {"cmd" {:any "value"}})

;; Performance/scalability
(def large-independent-instruction
  (into {}
        (for [i (range 200)]
          [i {:test/add-id i}])))

(def deep-dependency-instruction
  (let [depth 50]
    (reduce (fn [inst i] (if (= i 0) (assoc inst i {:test/add-id i}) (assoc inst i {:commando/from [(dec i)]})))
            {}
            (range depth))))

(def wide-fan-out-instruction
  (let [base {"base" {:test/add-id "shared"}}]
    (into base
          (for [i (range 100)]
            [i {:commando/from ["base"]}]))))

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
                  :point-key :ARG}})

(def custom-registry [custom-op-cmd custom-arg-cmd])

;; Helper-integration instructions
(def value-ref-instruction
  {"value" 42
   "ref" {:commando/from ["value"]}})

;; Structure test instructions
(def structure-map-instruction
  {"map" {:a 1
          :b 2
          :c 3}
   "=" {:commando/from ["map" :a]}})
(def structure-vector-instruction
  {"vector" [1 2 3]
   "=" {:commando/from ["vector" 0]}})
(def structure-set-instruction
  {"set" #{1 2 3}
   "=" {:commando/from ["set" 0]}})
(def structure-list-instruction
  {"list" (list 1 2 3)
   "=" {:commando/from ["list" 0]}})

;; Instruction-command test instructions
(def sum-collection-instruction
  {"0" {:commando/from ["=SUM"]
        := (fn [e] (apply + e))}
   "1" 1
   "2" {:container {:commando/from ["1"]
                    := inc}}
   "3" {:container {:commando/from ["2"]
                    := :container}}
   "=SUM" [{:commando/from ["1"]}
           {:commando/from ["2"]
            := :container}
           {:commando/from ["3"]
            := :container}]})

(def unexisting-path-instruction
  {"1" 1
   "2" {:container {:commando/from ["UNEXISTING_PATH"]}}})

;; Custom instruction sets
(def custom-instruction-flat
  {"A" 5
   "B" 10
   "result-multiply-1" {:OP :MULTIPLY
                        :ARGS [{:ARG ["A"]} 4]}
   "result-multiply-2" {:OP :MULTIPLY
                        :ARGS [{:ARG ["B"]} 2]}
   "result" {:OP :SUMM
             :ARGS [{:ARG ["result-multiply-1"]} {:ARG ["result-multiply-2"]} 1]}})

(def custom-instruction-nested
  {"A" 5
   "B" 10
   "result" {:OP :SUMM
             :ARGS [{:OP :MULTIPLY
                     :ARGS [{:ARG ["A"]} 4]}
                    {:OP :MULTIPLY
                     :ARGS [{:ARG ["B"]} 2]}
                    1]}})

;; Test data for execute-function-comprehensive-test
(def registry-from-spec [cmds-builtin/command-from-spec])
(def test-instruction
  {"source" 42
   "ref" {:commando/from ["source"]}})

(def basic-from-registry [cmds-builtin/command-from-spec test-add-id-command])
(def nested-instruction {"level1" {"level2" {"cmd" {:test/add-id "deep"}}}})
(def vector-instruction {"items" [{:test/add-id "first"} {:test/add-id "second"}]})
(def mixed-keys-instruction
  {"string-key" {:test/add-id "str"}
   :keyword-key {:test/add-id "kw"}
   42 {:test/add-id "num"}})
(def large-instruction
  (into {}
        (for [i (range 100)]
          [i {:test/add-id i}])))
(def deep-nested-instruction {0 {1 {2 {3 {4 {5 {6 {7 {8 {9 {"cmd" {:test/add-id "deep"}}}}}}}}}}}})


(def add-id-test-instruction {"cmd" {:test/add-id "test"}})

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

(def build-compiler-test-data
  {:valid-registry [cmds-builtin/command-from-spec]
   :valid-instruction {"a" 1
                       "b" {:commando/from ["a"]}}
   :cyclic-instruction {"a" {:commando/from ["b"]}
                        "b" {:commando/from ["a"]}}
   :malformed-registry [{:type :broken
                         :recognize-fn "not-a-function"}]
   :basic-registry [cmds-builtin/command-from-spec test-add-id-command]
   :basic-instruction {"cmd" {:test/add-id "value"}
                       "ref" {:commando/from ["cmd"]}}
   :invalid-ref-instruction {"ref" {:commando/from ["nonexistent"]}}
   :cmd-instruction {"cmd" {:test/add-id "value"}}
   :large-instruction (into {} (map #(vector (str %) {:test/add-id %}) (range 50)))})

(deftest build-compiler-test
  (testing "Status handling"
    (is (= :ok
           (:status (commando/build-compiler (:valid-registry build-compiler-test-data)
                                             (:valid-instruction build-compiler-test-data))))
        "Returns :ok status for valid registry and instruction")
    (is (status-map-contains-error? (commando/build-compiler (:valid-registry build-compiler-test-data)
                                                             (:cyclic-instruction build-compiler-test-data))
                                    "Commando. sort-entities-by-deps. Detected cyclic dependency")
        "Returns :failed status for cyclic dependencies")
    (is (status-map-contains-error? (commando/build-compiler (:malformed-registry build-compiler-test-data)
                                                             (:valid-instruction build-compiler-test-data))
                                    "Invalid registry specification")
        "Returns :failed status for malformed registry"))
  (testing "Basic functionality"
    (let [compiler (commando/build-compiler (:basic-registry build-compiler-test-data)
                                            (:basic-instruction build-compiler-test-data))]
      (is (= :ok (:status compiler)) "Compiler contain :status == :ok")
      (is (not-empty (:registry compiler)) "Compiler contain :registry")
      (is (= [(cm/->CommandMapPath ["cmd"] {:type :test/add-id}) (cm/->CommandMapPath ["ref"] {:type :commando/from})]
             (:internal/cm-running-order compiler))
          "Compiler contain :internal/cm-running-order")))
  (testing "Error scenarios"
    (is (= :failed
           (:status (commando/build-compiler [cmds-builtin/command-from-spec]
                                             (:invalid-ref-instruction build-compiler-test-data))))
        "Invalid reference causes failure")
    (is (status-map-contains-error?
         (commando/build-compiler [cmds-builtin/command-from-spec] (:invalid-ref-instruction build-compiler-test-data))
         "Commando. Point dependency failed: key ':commando/from' references non-existent path [\"nonexistent\"]")
        "Error information is populated")
    (is (status-map-contains-error? (commando/build-compiler [] (:cmd-instruction build-compiler-test-data))
                                    "Invalid registry specification")
        "Error cause the empty registry"))
  (testing "Edge cases"
    (is (commando/ok? (commando/build-compiler [test-add-id-command] {"data" "no-commands"}))
        "Registry with no matching commands")
    (is (status-map-contains-error? (commando/build-compiler (repeat 5 test-add-id-command)
                                                             (:large-instruction build-compiler-test-data))
                                    "Invalid registry specification")
        "duplicate commands in registry cause an error")
    (is (= 50
           (count (:internal/cm-running-order (commando/build-compiler [test-add-id-command]
                                                                       (:large-instruction build-compiler-test-data)))))
        "All commands processed")))

(def relative-path-instruction
  {"1" 1
   "2" {"container" {:commando/from ["../" "../" "1"]}}
   "3" {"container" {:commando/from ["../" "../" "2"]}}})

(def base-instruction-compiler
  {"0" {:commando/from ["=SUM"]
        := (fn [e] (apply + e))}
   "1" 1
   "2" {"container" {:commando/from ["1"]}}
   "3" {"container" {:commando/from ["2"]
                     := "container"}}
   "=SUM" [{:commando/from ["1"]}
           {:commando/from ["2"]
            := "container"}
           {:commando/from ["3"]
            := "container"}]})

(def toplevel-vector-instruction
  [{:value 10}
   {:commando/from [0 :value]
    := inc}
   {:commando/from [1]
    := (partial * 2)}])


(def compiler (commando/build-compiler full-registry-all base-instruction-compiler))

(deftest execute-test
  (testing "Status"
    (is (commando/ok? (commando/execute registry-from-spec test-instruction)) "Status :ok when successful")
    (is (= :ok
           (:status (commando/execute (commando/build-compiler registry-from-spec test-instruction) test-instruction)))
        "Pre-compiled compiler usage also returns :ok when successful")
    (is (= (:status (commando/execute registry-from-spec test-instruction))
           (:status (commando/execute (commando/build-compiler registry-from-spec test-instruction) test-instruction)))
        "Registry and compiler produce identical status results")
    (is (= :ok
           (:status (commando/execute basic-from-registry
                                      {"data" 123
                                       "info" "text"})))
        "Instruction with no commands succeeds")
    (is (commando/ok? (commando/execute basic-from-registry mixed-keys-instruction)) "Mixed data types as keys succeed")
    (is (commando/failed? (commando/execute [] empty-registry-instruction)))
    (is (commando/failed? (commando/execute error-registry failing-case-instruction)))
    (is (commando/failed? (commando/execute [cmds-builtin/command-from-spec] invalid-ref-instruction)))
    (is (not-empty (:errors (commando/execute [cmds-builtin/command-from-spec] invalid-ref-instruction))))
    (is (commando/failed? (commando/execute [cmds-builtin/command-from-spec] circular-instruction))
        "Circular dependencies")
    (is (commando/failed? (commando/execute [invalid-cmd] invalid-validation-instruction)) "Invalid command validation")
    (is (commando/failed? (commando/execute [throwing-cmd] throwing-recognition-instruction))
        "Command recognition exception")
    (let [result (commando/execute [cmds-builtin/command-from-spec] unexisting-path-instruction)]
      (is (commando/failed? result))
      (is (=
            (:errors result)
            [{:message
              "Commando. Point dependency failed: key ':commando/from' references non-existent path [\"UNEXISTING_PATH\"]",
              :path ["2" :container],
              :command {:commando/from ["UNEXISTING_PATH"]}}
             {:message "Corrupted compiler structure"}])))
    (is (commando/failed? (commando/execute [cmds-builtin/command-apply-spec] {"plain" {:commando/apply [1 2 3]}}))
        "Missing := parameter causes validation failure"))
  (testing "Basic cases"
    (is (= 42 (get-in (commando/execute registry-from-spec test-instruction) [:instruction "ref"]))
        "Command executed correctly")
    (is (= 42 (get-in (commando/execute registry-from-spec test-instruction) [:instruction "source"]))
        "Static value preserved")
    (is (= 42
           (get-in (commando/execute (commando/build-compiler registry-from-spec test-instruction) test-instruction)
                   [:instruction "ref"]))
        "Pre-compiled execution works correctly")
    (is (= 42
           (get-in (commando/execute (commando/build-compiler registry-from-spec test-instruction) test-instruction)
                   [:instruction "source"]))
        "Pre-compiled compiler static value preserved")
    (is (= (:instruction (commando/execute registry-from-spec test-instruction))
           (:instruction (commando/execute (commando/build-compiler registry-from-spec test-instruction)
                                           test-instruction)))
        "Registry and compiler produce identical instruction results")
    (is (commando/ok? (commando/execute basic-from-registry {})) "Empty instruction succeeds")
    (is (= {} (:instruction (commando/execute basic-from-registry {})))
        "Empty instruction preserves input instruction map")
    (is (= {"data" 123
            "info" "text"}
           (->> {"data" 123
                 "info" "text"}
                (commando/execute basic-from-registry)
                :instruction))
        "Instruction with no commands preserves data")
    (is (contains? (get-in (commando/execute basic-from-registry nested-instruction)
                           [:instruction "level1" "level2" "cmd"])
                   :id)
        "Nested command executes")
    (is (contains? (get-in (commando/execute basic-from-registry vector-instruction) [:instruction "items" 0]) :id)
        "First vector item has id")
    (is (contains? (get-in (commando/execute basic-from-registry vector-instruction) [:instruction "items" 1]) :id)
        "Second vector item has id")
    (is (contains? (get-in (commando/execute basic-from-registry mixed-keys-instruction) [:instruction "string-key"])
                   :id)
        "String key command executes")
    (is (contains? (get-in (commando/execute basic-from-registry mixed-keys-instruction) [:instruction :keyword-key])
                   :id)
        "Keyword key command executes")
    (is (contains? (get-in (commando/execute basic-from-registry mixed-keys-instruction) [:instruction 42]) :id)
        "Number key command executes")
    (is (every? #(contains? (get-in (commando/execute basic-from-registry large-instruction) [:instruction %]) :id)
                (range 100))
        "All large instruction commands execute")
    (is (contains? (get-in (commando/execute basic-from-registry deep-nested-instruction)
                           [:instruction 0 1 2 3 4 5 6 7 8 9 "cmd"])
                   :id)
        "Deep nested command executes")
    (is (= {"cmd" {:test/add-id "value"}} (:instruction (commando/execute [] empty-registry-instruction)))
        "empty registry preserves instruction")
    (is (= 200
           (count (filter #(contains? % :id)
                          (vals (:instruction (commando/execute basic-from-registry large-independent-instruction)))))))
    (is (every? #(contains? (get-in (commando/execute basic-from-registry deep-dependency-instruction) [:instruction %])
                            :id)
                (range 50)))
    (is (contains? (get-in (commando/execute basic-from-registry wide-fan-out-instruction) [:instruction "base"]) :id))
    (is (every? #(contains? (get-in (commando/execute basic-from-registry wide-fan-out-instruction) [:instruction %])
                            :id)
                (range 100)))
    (is (= 5
           (get-in (commando/execute [cmds-builtin/command-from-spec] sum-collection-instruction) [:instruction "0"])))
    (is (= {"A" 5
            "B" 10
            "result-multiply-1" 20
            "result-multiply-2" 20
            "result" 41}
           (:instruction (commando/execute custom-registry custom-instruction-flat))))
    (is (= {"A" 5
            "B" 10
            "result" 41}
           (:instruction (commando/execute custom-registry custom-instruction-nested))))
    (is (= {"0" {:final "5"}}
           (->> {"0" {:commando/apply {"1" {:commando/apply {"2" {:commando/apply {"3" {:commando/apply {"4" {:final
                                                                                                              "5"}}
                                                                                        := #(get % "4")}}
                                                                  := #(get % "3")}}
                                            := #(get % "2")}}
                      := #(get % "1")}}
                (commando/execute [cmds-builtin/command-apply-spec])
                :instruction))
        "Commands inside commands are executed correctly")
    (is (= "john"
           (get-in (:instruction (commando/execute [cmds-builtin/command-from-spec]
                                                   {"source" {:user-name "john"
                                                              :age 25}
                                                    "name" {:commando/from ["source"]
                                                            := :user-name}}))
                   ["name"]))
        "Value extracted correctly with := in commando/from using keyword")
    (is (= 25
           (get-in (:instruction (commando/execute [cmds-builtin/command-from-spec]
                                                   {"source" {"age" 25}
                                                    "age" {:commando/from ["source"]
                                                           := "age"}}))
                   ["age"]))
        "Value extracted correctly with := in commando/from using string")
    (is (= 15
           (get-in (:instruction (commando/execute [cmds-builtin/command-from-spec]
                                                   {"numbers" [1 2 3 4 5]
                                                    "sum" {:commando/from ["numbers"]
                                                           := #(reduce + %)}}))
                   ["sum"]))
        "commando/from  := syntax applying function works")
    (is (= 1
           (get-in (:instruction (commando/execute [cmds-builtin/command-from-spec]
                                                   {"numbers" [1 2 3 4 5]
                                                    "first" {:commando/from ["numbers"]
                                                             := first}}))
                   ["first"]))
        "commando/from  := syntax applying function works")
    (is (nil? (get-in (:instruction (commando/execute [cmds-builtin/command-from-spec]
                                                      {"source" {:a 1
                                                                 :b 2}
                                                       "missing" {:commando/from ["source"]
                                                                  := :nonexistent}}))
                      ["missing"]))
        "commando/from := nil returned when value is missing")
    (is (nil? (get-in (:instruction (commando/execute [cmds-builtin/command-from-spec]
                                                      {"source" {:a 1
                                                                 :b 2}
                                                       "missing" {:commando/from ["source"]
                                                                  := "bóg"}}))
                      ["missing"]))
        "commando/from := nil returned when value is missing")
    (is (= '(20 40 60)
           (get-in (:instruction (commando/execute [cmds-builtin/command-apply-spec cmds-builtin/command-from-spec]
                                                   {"base" [10 20 30]
                                                    "doubled" {:commando/apply {:commando/from ["base"]}
                                                               := #(map (partial * 2) %)}}))
                   ["doubled"]))
        "commando/apply works with just a command as a value")
    (testing "Single command types"
      (is (= 10 (get-in (commando/execute [cmds-builtin/command-from-spec] from-instruction) [:instruction "b"])))
      (is (= 6 (get-in (commando/execute [cmds-builtin/command-fn-spec] fn-instruction) [:instruction "calc"])))
      (is
       (= 3 (get-in (commando/execute [cmds-builtin/command-apply-spec] apply-instruction) [:instruction "transform"])))
      (is (contains? (get-in (commando/execute [test-add-id-command] add-id-test-instruction) [:instruction "cmd"])
                     :id)))
    (testing "Mixed command types in single instruction"
      (is (= 100 (get-in (commando/execute full-registry-all mixed-instruction) [:instruction "source"])))
      (is (= 200 (get-in (commando/execute full-registry-all mixed-instruction) [:instruction "doubled"])))
      (is (= "200" (get-in (commando/execute full-registry-all mixed-instruction) [:instruction "processed"])))
      (is (contains? (get-in (commando/execute full-registry-all mixed-instruction) [:instruction "metadata"]) :id)))
    (testing "Linear dependency chain"
      (is (contains? (get-in (commando/execute basic-from-registry linear-chain-instruction) [:instruction "step1"])
                     :id))
      (is (contains? (get-in (commando/execute basic-from-registry linear-chain-instruction) [:instruction "step2"])
                     :id))
      (is (contains? (get-in (commando/execute basic-from-registry linear-chain-instruction) [:instruction "step3"])
                     :id))
      (is (contains? (get-in (commando/execute basic-from-registry linear-chain-instruction) [:instruction "step4"])
                     :id)))
    (testing "Fan-out dependencies"
      (is (contains? (get-in (commando/execute basic-from-registry fan-out-instruction) [:instruction "base"]) :id))
      (is (contains? (get-in (commando/execute basic-from-registry fan-out-instruction) [:instruction "branch1"]) :id))
      (is (contains? (get-in (commando/execute basic-from-registry fan-out-instruction) [:instruction "branch2"]) :id))
      (is (contains? (get-in (commando/execute basic-from-registry fan-out-instruction) [:instruction "branch3"]) :id)))
    (testing "Diamond dependencies"
      (is (contains? (get-in (commando/execute basic-from-registry diamond-instruction) [:instruction "root"]) :id))
      (is (contains? (get-in (commando/execute basic-from-registry diamond-instruction) [:instruction "left"]) :id))
      (is (contains? (get-in (commando/execute basic-from-registry diamond-instruction) [:instruction "right"]) :id))
      (is (contains? (get-in (commando/execute basic-from-registry diamond-instruction) [:instruction "merge"]) :id)))
    (testing "Hierarchical all-inside dependencies"
      (is (contains? (get-in (commando/execute basic-from-registry hierarchical-instruction) [:instruction "parent"])
                     :id))
      (is (contains? (get-in (commando/execute basic-from-registry hierarchical-instruction)
                             [:instruction "parent" "child1"])
                     :id))
      (is (contains? (get-in (commando/execute basic-from-registry hierarchical-instruction)
                             [:instruction "parent" "child2"])
                     :id)))
    (testing ":point dependency lookup in set/list cause a failure"
      (is (and (commando/ok? (commando/execute [cmds-builtin/command-from-spec] structure-map-instruction))
               (= 1
                  (get-in (commando/execute [cmds-builtin/command-from-spec] structure-map-instruction)
                          [:instruction "="]))))
      (is (and (commando/ok? (commando/execute [cmds-builtin/command-from-spec] structure-vector-instruction))
               (= 1
                  (get-in (commando/execute [cmds-builtin/command-from-spec] structure-vector-instruction)
                          [:instruction "="]))))
      (is (commando/failed? (commando/execute [cmds-builtin/command-from-spec] structure-set-instruction)))
      (is (commando/failed? (commando/execute [cmds-builtin/command-from-spec] structure-list-instruction))))
    (testing "Navigation with relative path ../"
      (is (= 1
             (get-in (:instruction (commando/execute [cmds-builtin/command-from-spec] relative-path-instruction))
                     ["2" "container"]))
          "Parent path resolves to correct value")
      (is (= {"container" 1}
             (get-in (:instruction (commando/execute [cmds-builtin/command-from-spec] relative-path-instruction))
                     ["3" "container"]))
          "Nested parent path with transformation works"))
    (testing "Top-level Vector Instruction"
      (let [result (commando/execute [cmds-builtin/command-from-spec] toplevel-vector-instruction)]
        (is (commando/ok? result) "This type of instruction is also acceptable")
        (is (= [{:value 10} 11 22] (:instruction result))
            "Result of toplevel-vector instruction not match with example")))
    (testing "Compiler reuse optimization"
      (let [result1 (commando/execute compiler base-instruction-compiler)
            modified-instruction (assoc base-instruction-compiler "1" 1000)
            result2 (commando/execute compiler modified-instruction)]
        (is (commando/ok? compiler) "Compiler builds successfully")
        (is (commando/ok? result1) "Original execution succeeds")
        (is (commando/ok? result2) "Modified execution succeeds")
        (is (= 3 (get-in (:instruction result1) ["0"])) "Original calculation correct")
        (is (= 3000 (get-in (:instruction result2) ["0"])) "Modified calculation correct")))))
