(ns commando.impl.graph-test
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

(def chain-cmd-a (cm/command-map-path [:a] cmds-builtin/command-from-spec))
(def chain-cmd-b (cm/command-map-path [:b] cmds-builtin/command-from-spec))
(def chain-cmd-c (cm/command-map-path [:c] test-add-id-command))

(def diamond-cmd-a (cm/command-map-path [:a] cmds-builtin/command-from-spec))
(def diamond-cmd-b (cm/command-map-path [:b] cmds-builtin/command-from-spec))
(def diamond-cmd-c (cm/command-map-path [:c] cmds-builtin/command-from-spec))
(def diamond-cmd-d (cm/command-map-path [:d] test-add-id-command))

(def circular-cmd-a (cm/command-map-path [:a] cmds-builtin/command-from-spec))
(def circular-cmd-b (cm/command-map-path [:b] cmds-builtin/command-from-spec))

(deftest sort-entities-by-deps
  (testing "Status handling"
    (is (commando/failed? (#'commando/step-sort-commands-by-deps
                            {:status :failed :instruction {} :registry registry :internal/cm-list []}))
        "Failed status is preserved")
    (is (commando/ok? (#'commando/step-sort-commands-by-deps
                        {:status :ok :instruction {:a 1} :registry registry :internal/cm-list []}))
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
          result (#'commando/step-sort-commands-by-deps deps-map)
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
          result (#'commando/step-sort-commands-by-deps deps-map)
          order (:internal/cm-running-order result)]
      (is (commando/ok? result) "Successfully sorts diamond dependency")
      (is (= 4 (count order)) "Returns all commands in order")
      (is (< (.indexOf order diamond-cmd-d) (.indexOf order diamond-cmd-b)) "D executes before B")
      (is (< (.indexOf order diamond-cmd-d) (.indexOf order diamond-cmd-c)) "D executes before C")
      (is (< (.indexOf order diamond-cmd-b) (.indexOf order diamond-cmd-a)) "B executes before A")))
  (testing "Circular dependency detection"
    (let [deps-map {:status :ok
                    :instruction {:a {:commando/from [:b]}
                                  :b {:commando/from [:a]}}
                    :registry registry
                    :internal/cm-dependency {circular-cmd-a #{circular-cmd-b}
                                             circular-cmd-b #{circular-cmd-a}}}
          result (#'commando/step-sort-commands-by-deps deps-map)]
      (is (commando/failed? result) "Detects circular dependency and returns failed status")
      (is (some #(re-find #"cyclic dependency" %) (map :message (:errors result)))
          "Error message mentions cyclic dependency"))))
