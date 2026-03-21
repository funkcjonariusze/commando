(ns commando.impl.finding-commands-test
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

(deftest find-commands
  (testing "Basic cases"
    (is (= [(cm/->CommandMapPath [] #'commando-registry/default-command-map-spec)]
          (:internal/cm-list (#'commando/find-commands
                               {:status :ok
                                :instruction {}
                                :registry registry})))
      "Empty instruction return _map command")
    (is (= [(cm/->CommandMapPath [] #'commando-registry/default-command-map-spec)
            (cm/->CommandMapPath [:some-val] #'commando-registry/default-command-map-spec)
            (cm/->CommandMapPath [:some-other] #'commando-registry/default-command-value-spec)
            (cm/->CommandMapPath [:my-value] #'commando-registry/default-command-value-spec)
            (cm/->CommandMapPath [:i] #'commando-registry/default-command-map-spec)
            (cm/->CommandMapPath [:v] #'commando-registry/default-command-vec-spec)
            (cm/->CommandMapPath [:some-val :a] #'commando-registry/default-command-value-spec)
            (cm/->CommandMapPath [:i :am] #'commando-registry/default-command-map-spec)
            (cm/->CommandMapPath [:i :am :deep] #'commando-registry/default-command-value-spec)]
          (:internal/cm-list (#'commando/find-commands
                               {:status :ok
                                :instruction {:some-val {:a 2}
                                              :some-other 3
                                              :my-value :is-here
                                              :i {:am {:deep :nested}}
                                              :v []}
                                :registry registry})))
        "Instruction return internal commands _map, _vec, _value.")
    (is (= [(cm/->CommandMapPath [] #'commando-registry/default-command-map-spec)
            (cm/->CommandMapPath [:set] #'commando-registry/default-command-value-spec)
            (cm/->CommandMapPath [:list] #'commando-registry/default-command-value-spec)
            (cm/->CommandMapPath [:primitive] #'commando-registry/default-command-value-spec)
            (cm/->CommandMapPath [:java-obj] #'commando-registry/default-command-value-spec)]
           (:internal/cm-list (#'commando/find-commands
                                {:status :ok
                                 :instruction {:set #{:commando/from [:target]}
                                               :list (list {:commando/from [:target]})
                                               :primitive 42
                                               :java-obj #?(:clj (java.util.Date.)
                                                            :cljs (js/Date.))}
                                 :registry registry})))
        "Any type that not Map,Vector(and registry not contain other commands) became a _value standart internal command")
    (is (= [(cm/->CommandMapPath [] #'commando-registry/default-command-map-spec)
            (cm/->CommandMapPath [:set] #'commando-registry/default-command-value-spec)
            (cm/->CommandMapPath [:list] #'commando-registry/default-command-value-spec)
            (cm/->CommandMapPath [:valid] #'commando-registry/default-command-vec-spec)
            (cm/->CommandMapPath [:target] #'commando-registry/default-command-value-spec)
            (cm/->CommandMapPath [:valid 0] cmds-builtin/command-from-spec)]
           (:internal/cm-list (#'commando/find-commands
                                {:status :ok
                                 :instruction {:set #{:not-found}
                                               :list (list :not-found)
                                               :valid [{:commando/from [:target]}]
                                               :target 42}
                                 :registry registry})))
        "commando/from find and returned with corresponding command-map-path object")
    (is (=
          [(cm/->CommandMapPath [] #'commando-registry/default-command-map-spec)
           (cm/->CommandMapPath [:a] #'commando-registry/default-command-map-spec)
           (cm/->CommandMapPath [:target] #'commando-registry/default-command-value-spec)
           (cm/->CommandMapPath [:a "some"] #'commando-registry/default-command-map-spec)
           (cm/->CommandMapPath [:a "some" :c] #'commando-registry/default-command-vec-spec)
           (cm/->CommandMapPath [:a "some" :c 0] #'commando-registry/default-command-value-spec)
           (cm/->CommandMapPath [:a "some" :c 1] cmds-builtin/command-from-spec)]
          (:internal/cm-list (#'commando/find-commands
                               {:status :ok
                                :instruction {:a {"some" {:c [:some {:commando/from [:target]}]}}
                                              :target 42}
                                :registry registry})))
      "Example of usage commando/from inside of deep map")
    (is (= :failed
          (:status (#'commando/find-commands {:status :failed})))
      "Failed status is preserved")
    (is
     (let [mixed-keys-result (:internal/cm-list (#'commando/find-commands
                                                  {:status :ok
                                                   :instruction {"string-key" {:commando/from [:a]}
                                                                 :keyword-key {:commando/from [:a]}
                                                                 42 {:commando/from [:a]}
                                                                 :a 1}
                                                   :registry registry}))]
       (is (some #(= (cm/command-path %) ["string-key"]) mixed-keys-result) "Correctly handles string keys in paths")
       (is (some #(= (cm/command-path %) [:keyword-key]) mixed-keys-result) "Correctly handles keyword keys in paths")
       (is (some #(= (cm/command-path %) [42]) mixed-keys-result) "Correctly handles numeric keys in paths")))))
