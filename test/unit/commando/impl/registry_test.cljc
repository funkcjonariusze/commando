(ns commando.impl.registry-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as string]
   [commando.core :as commando]
   [commando.commands.builtin :as cmds-builtin]
   [commando.impl.registry :as registry]))

(def custom-spec
  {:type :custom/upper
   :recognize-fn #(and (map? %) (contains? % :custom/upper))
   :apply (fn [_instruction _command m]
            (string/upper-case (:custom/upper m)))
   :dependencies {:mode :none}})

(deftest build-registry-from-vector
  (testing "Build registry from a vector of specs"
    (let [r (registry/build [cmds-builtin/command-from-spec
                             cmds-builtin/command-fn-spec])]
      (is (registry/built? r))
      (is (= #{:commando/from :commando/fn} (set (map :type (:registry r)))))
      (is (= 2 (count (:registry r)))))))

(deftest build-registry-preserves-order
  (testing "Build registry preserves vector order"
    (let [r (registry/build [cmds-builtin/command-fn-spec
                             cmds-builtin/command-from-spec])]
      (is (= [:commando/fn :commando/from] (mapv :type (:registry r)))))))

(deftest registry-create-from-vector
  (testing "registry-create accepts a vector and preserves order"
    (let [r (commando/registry-create
              [cmds-builtin/command-fn-spec
               cmds-builtin/command-from-spec])]
      (is (registry/built? r))
      (is (= [:commando/fn :commando/from] (mapv :type (:registry r)))))))

(deftest registry-create-idempotent
  (testing "Passing an already-built registry returns it unchanged"
    (let [r (commando/registry-create [cmds-builtin/command-from-spec])
          r2 (commando/registry-create r)]
      (is (identical? r r2)))))

(deftest registry-add-adds-spec
  (testing "registry-add adds a new spec to a built registry"
    (let [r  (commando/registry-create [cmds-builtin/command-from-spec])
          r2 (commando/registry-add r custom-spec)]
      (is (registry/built? r2))
      (is (some #(= :custom/upper (:type %)) (:registry r2)))
      (is (some #(= :commando/from (:type %)) (:registry r2))))))

(deftest registry-add-replaces-spec
  (testing "registry-add replaces an existing spec"
    (let [r   (commando/registry-create [cmds-builtin/command-from-spec
                                         custom-spec])
          new-spec (assoc custom-spec :apply (fn [_ _ m] (string/lower-case (:custom/upper m))))
          r2  (commando/registry-add r new-spec)]
      (is (= (:apply new-spec)
             (:apply (first (filter #(= :custom/upper (:type %)) (:registry r2)))))))))

(deftest registry-remove-removes-spec
  (testing "registry-remove removes a spec from registry"
    (let [r  (commando/registry-create [cmds-builtin/command-from-spec
                                        custom-spec])
          r2 (commando/registry-remove r :custom/upper)]
      (is (registry/built? r2))
      (is (not (some #(= :custom/upper (:type %)) (:registry r2))))
      (is (some #(= :commando/from (:type %)) (:registry r2))))))

(deftest registry-execute-with-vector
  (testing "Execute works when registry was created from a vector"
    (let [result (commando/execute
                   [cmds-builtin/command-from-spec]
                   {"a" 1 "b" {:commando/from ["a"]}})]
      (is (commando/ok? result))
      (is (= {"a" 1 "b" 1} (:instruction result))))))

(deftest registry-execute-with-built
  (testing "Execute works when registry was pre-built"
    (let [r (commando/registry-create [cmds-builtin/command-from-spec])
          result (commando/execute r {"a" 1 "b" {:commando/from ["a"]}})]
      (is (commando/ok? result))
      (is (= {"a" 1 "b" 1} (:instruction result))))))
