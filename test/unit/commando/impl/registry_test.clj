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

(deftest build-registry-from-map
  (testing "Build registry from a map of specs"
    (let [r (registry/build {:commando/from cmds-builtin/command-from-spec
                             :commando/fn   cmds-builtin/command-fn-spec})]
      (is (registry/built? r))
      (is (= #{:commando/from :commando/fn} (set (keys (:registry r)))))
      (is (= 2 (count (:registry-order r)))))))

(deftest build-registry-from-map-with-order
  (testing "Build registry from a map with explicit order"
    (let [r (registry/build
              {:commando/from cmds-builtin/command-from-spec
               :commando/fn   cmds-builtin/command-fn-spec}
              {:registry-order [:commando/fn :commando/from]})]
      (is (= [:commando/fn :commando/from] (:registry-order r))))))

(deftest registry-create-from-vector
  (testing "registry-create accepts a vector and preserves order"
    (let [r (commando/registry-create
              [cmds-builtin/command-fn-spec
               cmds-builtin/command-from-spec])]
      (is (registry/built? r))
      (is (= [:commando/fn :commando/from] (:registry-order r)))
      (is (= #{:commando/fn :commando/from} (set (keys (:registry r))))))))

(deftest registry-create-idempotent
  (testing "Passing an already-built registry returns it unchanged"
    (let [r (commando/registry-create [cmds-builtin/command-from-spec])
          r2 (commando/registry-create r)]
      (is (identical? r r2)))))

(deftest registry-assoc-adds-spec
  (testing "registry-assoc adds a new spec to a built registry"
    (let [r  (commando/registry-create {:commando/from cmds-builtin/command-from-spec})
          r2 (commando/registry-assoc r :custom/upper custom-spec)]
      (is (registry/built? r2))
      (is (contains? (:registry r2) :custom/upper))
      (is (some #{:custom/upper} (:registry-order r2)))
      (is (contains? (:registry r2) :commando/from)))))

(deftest registry-assoc-replaces-spec
  (testing "registry-assoc replaces an existing spec"
    (let [r   (commando/registry-create {:commando/from cmds-builtin/command-from-spec
                                         :custom/upper  custom-spec})
          new-spec (assoc custom-spec :apply (fn [_ _ m] (string/lower-case (:custom/upper m))))
          r2  (commando/registry-assoc r :custom/upper new-spec)]
      (is (= (get-in r2 [:registry :custom/upper :apply])
             (:apply new-spec))))))

(deftest registry-dissoc-removes-spec
  (testing "registry-dissoc removes a spec from registry"
    (let [r  (commando/registry-create {:commando/from cmds-builtin/command-from-spec
                                        :custom/upper custom-spec})
          r2 (commando/registry-dissoc r :custom/upper)]
      (is (registry/built? r2))
      (is (not (contains? (:registry r2) :custom/upper)))
      (is (not (some #{:custom/upper} (:registry-order r2))))
      (is (contains? (:registry r2) :commando/from)))))

(deftest registry-execute-with-vector
  (testing "Execute works when registry was created from a vector"
    (let [result (commando/execute
                   [cmds-builtin/command-from-spec]
                   {"a" 1 "b" {:commando/from ["a"]}})]
      (is (commando/ok? result))
      (is (= {"a" 1 "b" 1} (:instruction result))))))

(deftest registry-execute-with-map
  (testing "Execute works when registry was created from a map"
    (let [result (commando/execute
                   {:commando/from cmds-builtin/command-from-spec}
                   {"a" 1 "b" {:commando/from ["a"]}})]
      (is (commando/ok? result))
      (is (= {"a" 1 "b" 1} (:instruction result))))))

(deftest registry-execute-with-built
  (testing "Execute works when registry was pre-built"
    (let [r (commando/registry-create [cmds-builtin/command-from-spec])
          result (commando/execute r {"a" 1 "b" {:commando/from ["a"]}})]
      (is (commando/ok? result))
      (is (= {"a" 1 "b" 1} (:instruction result))))))
