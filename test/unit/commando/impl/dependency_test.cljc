(ns commando.impl.dependency-test
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [commando.commands.builtin :as cmds-builtin]
   [commando.core             :as commando]
   [commando.impl.command-map :as cm]
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

;; -- Command path objects --

(def parent-cmd (cm/->CommandMapPath [:parent] test-add-id-command))
(def child-cmd (cm/->CommandMapPath [:parent :child] test-add-id-command))
(def target-cmd (cm/->CommandMapPath [:target] test-add-id-command))
(def ref-cmd (cm/->CommandMapPath [:ref] cmds-builtin/command-from-spec))

(def chain-cmd-a (cm/->CommandMapPath [:a] cmds-builtin/command-from-spec))
(def chain-cmd-b (cm/->CommandMapPath [:b] cmds-builtin/command-from-spec))
(def chain-cmd-c (cm/->CommandMapPath [:c] test-add-id-command))

(def diamond-cmd-a (cm/->CommandMapPath [:a] cmds-builtin/command-from-spec))
(def diamond-cmd-b (cm/->CommandMapPath [:b] cmds-builtin/command-from-spec))
(def diamond-cmd-c (cm/->CommandMapPath [:c] cmds-builtin/command-from-spec))
(def diamond-cmd-d (cm/->CommandMapPath [:d] test-add-id-command))

(def deep-shallow (cm/->CommandMapPath [:deep :nested :cmd] cmds-builtin/command-from-spec))
(def shallow-target (cm/->CommandMapPath [:target] test-add-id-command))

(def sibling1 (cm/->CommandMapPath [:container :sib1] cmds-builtin/command-from-spec))
(def sibling2 (cm/->CommandMapPath [:container :sib2] test-add-id-command))

;; -- Status maps --

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

(def deep-cross-ref-map
  {:status :ok
   :instruction {:deep {:nested {:cmd {:commando/from [:target]}}}
                 :target {:test/add-id :fn}}
   :registry registry
   :internal/cm-list [deep-shallow shallow-target]})

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
    (is (commando/failed? (#'commando/build-deps-tree
                           {:status :ok
                            :instruction {:ref {:commando/from [:nonexistent]}}
                            :registry registry
                            :internal/cm-list [(cm/->CommandMapPath [:ref] cmds-builtin/command-from-spec)]}))
        "Returns failed status for non-existent path references")
    (is (commando/ok? (#'commando/build-deps-tree empty-ok-status-map)) "Success status with empty command list"))
  (testing "Dependency patterns"
    (let [deps (:internal/cm-dependency (#'commando/build-deps-tree all-inside-status-map))]
      (is (contains? (get deps parent-cmd) child-cmd) "Parent depends on child (all-inside)"))
    (is (contains? (get (:internal/cm-dependency (#'commando/build-deps-tree point-deps-status-map)) ref-cmd)
                   target-cmd)
        "Ref depends on target (point)")
    (let [deps (:internal/cm-dependency (#'commando/build-deps-tree chained-deps-map))]
      (is (contains? (get deps chain-cmd-a) chain-cmd-b) "A depends on B")
      (is (contains? (get deps chain-cmd-b) chain-cmd-c) "B depends on C")
      (is (empty? (get deps chain-cmd-c)) "C has no dependencies"))
    (let [deps (:internal/cm-dependency (#'commando/build-deps-tree diamond-deps-map))]
      (is (contains? (get deps diamond-cmd-b) diamond-cmd-d) "B depends on D")
      (is (contains? (get deps diamond-cmd-c) diamond-cmd-d) "C depends on D")
      (is (empty? (get deps diamond-cmd-d)) "D has no dependencies"))
    (let [deps (:internal/cm-dependency (#'commando/build-deps-tree deep-cross-ref-map))]
      (is (contains? (get deps deep-shallow) shallow-target) "Deep nested depends on shallow target"))
    (let [deps (:internal/cm-dependency (#'commando/build-deps-tree sibling-deps-map))]
      (is (contains? (get deps sibling1) sibling2) "Sibling1 depends on sibling2")))
  (testing "Complex multi-level dependency resolution"
    (let [large-instruction
          {:config {:database {:test/add-id :database}
                    :cache {:test/add-id :cache}}
           :users {:fetch {:commando/from [:config :database]}
                   :validate {:commando/from [:users :fetch]}}
           :products {:load {:test/add-id :products
                             :items {:fetch {:commando/from [:config :database]}}}
                      :cache {:commando/from [:products :load]}}
           :orders {:create {:commando/from [:users :validate]}
                    :prepare {:commando/from [:products :cache]}}}
          cmds (:internal/cm-list (#'commando/find-commands
                                    {:status :ok :instruction large-instruction :registry registry}))
          result (#'commando/build-deps-tree
                   {:status :ok :instruction large-instruction :registry registry :internal/cm-list cmds})
          deps (:internal/cm-dependency result)]
      (is (commando/ok? result) "Successfully processes large dependency tree")
      (is (contains? (get deps (cmd-by-path [:users :fetch] cmds)) (cmd-by-path [:config :database] cmds))
          "users.fetch depends on config.database")
      (is (contains? (get deps (cmd-by-path [:users :validate] cmds)) (cmd-by-path [:users :fetch] cmds))
          "users.validate depends on users.fetch")
      (is (contains? (get deps (cmd-by-path [:orders :create] cmds)) (cmd-by-path [:users :validate] cmds))
          "orders.create depends on users.validate")
      (is (contains? (get deps (cmd-by-path [:orders :prepare] cmds)) (cmd-by-path [:products :cache] cmds))
          "orders.prepare depends on products.cache")))
  (testing "Empty command list"
    (let [result (#'commando/build-deps-tree
                   {:status :ok :instruction {} :registry registry :internal/cm-list []})]
      (is (commando/ok? result) "Handles empty command list")
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
          "ref command depends on goal-1 command (parent path dependency)")))
  (testing ":none mode - no dependencies"
    (let [none-command {:type :test/none
                        :recognize-fn #(and (map? %) (contains? % :test/none))
                        :apply identity
                        :dependencies {:mode :none}}
          none-cmd (cm/->CommandMapPath [:standalone] none-command)
          test-status-map {:status :ok
                           :instruction {:standalone {:test/none :independent}}
                           :registry (commando/registry-create [none-command])
                           :internal/cm-list [none-cmd]}
          result (#'commando/build-deps-tree test-status-map)
          deps (:internal/cm-dependency result)]
      (is (commando/ok? result) "Successfully processes :none dependency")
      (is (empty? (get deps none-cmd)) "Command with :none mode has no dependencies"))))
