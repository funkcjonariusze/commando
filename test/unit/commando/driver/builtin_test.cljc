(ns commando.driver.builtin-test
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [commando.commands.builtin :as cmds]
   [commando.core             :as commando]
   [commando.impl.executing   :as executing]
   [clojure.string            :as str]))

;; ====================
;; :identity (default)
;; ====================

(deftest identity-driver-test
  (testing "Explicit :identity"
    (is (= "Kyiv"
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data "Kyiv"
               :city {:commando/from [:data] :=> :identity}})
            [:instruction :city]))))

  (testing "No :=> — default :identity returns full result"
    (is (= {:nested true}
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data {:nested true}
               :ref {:commando/from [:data]}})
            [:instruction :ref]))))

  (testing "String keys (JSON-compatible)"
    (is (= "Kyiv"
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {"data" "Kyiv"
               "city" {"commando-from" ["data"] "=>" ["identity"]}})
            [:instruction "city"])))))


;; ====================
;; :get-in
;; ====================

(deftest get-in-driver-test
  (testing "Explicit :get-in with path"
    (is (= "Kyiv"
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data {:address {:location {:city "Kyiv"}}}
               :city {:commando/from [:data] :=> [:get-in [:address :location :city]]}})
            [:instruction :city]))))

  (testing "String keys (JSON-compatible)"
    (is (= "Kyiv"
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {"data" {"address" {"city" "Kyiv"}}
               "city" {"commando-from" ["data"] "=>" ["get-in" ["address" "city"]]}})
            [:instruction "city"])))))

;; ====================
;; :get
;; ====================

(deftest get-driver-test
  (testing ":get driver extracts single key"
    (is (= "John"
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data {:name "John" :age 30}
               :name {:commando/from [:data] :=> [:get :name]}})
            [:instruction :name]))))

  (testing "String keys"
    (is (= "John"
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {"data" {"name" "John" "age" 30}
               "name" {"commando-from" ["data"] "=>" ["get" "name"]}})
            [:instruction "name"])))))

;; ====================
;; :select-keys
;; ====================

(deftest select-keys-driver-test
  (testing ":select-keys filters result keys"
    (is (= {:name "John" :email "j@x.com"}
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data {:name "John" :age 30 :email "j@x.com" :id 999}
               :subset {:commando/from [:data] :=> [:select-keys [:name :email]]}})
            [:instruction :subset]))))
  (testing "String keys"
    (is (= {"name" "John" "email" "j@x.com"}
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {"data" {"name" "John" "age" 30 "email" "j@x.com" "id" 999}
               "subset" {"commando-from" ["data"] "=>" ["select-keys" ["name" "email"]]}})
            [:instruction "subset"])))))

;; ====================
;; :fn
;; ====================

(deftest fn-driver-test
  (testing ":fn driver applies function to result"
    (is (= 7
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data 6
               :inc {:commando/from [:data] :=> [:fn inc]}})
            [:instruction :inc]))))

  (testing ":fn with keyword as accessor"
    (is (= 1
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data {:kwd 1}
               :val {:commando/from [:data] :=> [:fn :kwd]}})
            [:instruction :val])))))

;; ====================
;; :fn with commando/apply and commando/context
;; ====================

(deftest fn-driver-with-mixed-commands
  (testing ":fn driver with commando/apply"
    (is (= 2
          (get-in
            (commando/execute {:commando/apply cmds/command-apply-spec
                               :commando/from cmds/command-from-spec}
              {:value 1
               :result {:commando/apply {:commando/from [:value]}
                        :=> [:fn inc]}})
            [:instruction :result]))))
  (testing ":fn driver with commando/context"
    (let [ctx {:colors {:red "#FF0000" :blue "#0000FF"}}]
      (is (= "#0000FF"
            (get-in
              (commando/execute {:commando/context (cmds/command-context-spec ctx)}
                {:val {:commando/context [:colors] :=> [:fn :blue]}})
              [:instruction :val]))))))

;; ====================
;; :projection
;; ====================

(deftest projection-driver-test
  (testing "Rename and reshape fields"
    (is (= {:user-id "u-101" :city "Kyiv"}
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data {:id "u-101" :address {:location {:city "Kyiv"}}}
               :result {:commando/from [:data]
                        :=> [:projection [[:user-id :id]
                                          [:city [:address :location :city]]]]}})
            [:instruction :result]))))

  (testing "Simple key passthrough"
    (is (= {:id "u-101"}
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data {:id "u-101" :extra "stuff"}
               :result {:commando/from [:data]
                        :=> [:projection [[:id]]]}})
            [:instruction :result]))))

  (testing "String keys (JSON-compatible)"
    (is (= {"user-id" "u-101" "city" "Kyiv"}
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {"data" {"id" "u-101" "address" {"location" {"city" "Kyiv"}}}
               "result" {"commando-from" ["data"]
                         "=>" ["projection" [["user-id" "id"]
                                             ["city" ["address" "location" "city"]]]]}})
            [:instruction "result"])))))

;; ====================
;; Mutation + driver
;; ====================

(defmethod cmds/command-mutation :driver-test-create-user
  [_ {:keys [name email]}]
  {:id (str "u-" (hash name))
   :name name
   :email email
   :status :active
   :internal-score 42})

(deftest driver-with-mutation-test
  (testing "Mutation result filtered by :select-keys driver"
    (let [user (get-in
                 (commando/execute {:commando/mutation cmds/command-mutation-spec}
                   {:user {:commando/mutation :driver-test-create-user
                           :name "John"
                           :email "j@x.com"
                           :=> [:select-keys [:id :name :email :status]]}})
                 [:instruction :user])]
      (is (= #{:id :name :email :status} (set (keys user))))
      (is (nil? (:internal-score user))))))

;; ====================
;; Custom keyword driver
;; ====================

(defmethod executing/command-driver :uppercase
  [_ _params applied-result _command-data _instruction _command-path-obj]
  (if (string? applied-result)
    (str/upper-case applied-result)
    applied-result))

(deftest custom-keyword-driver-test
  (testing "Custom keyword driver without params"
    (is (= "JOHN"
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data "John"
               :name {:commando/from [:data] :=> :uppercase}})
            [:instruction :name])))))

;; ====================
;; Pipeline
;; ====================

(deftest pipe-driver-test
  (testing "Two-step pipeline: get then uppercase"
    (is (= "KYIV"
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data {:city "Kyiv" :zip "01001"}
               :result {:commando/from [:data] :=> [[:get :city] :uppercase]}})
            [:instruction :result]))))
  
  (testing "Two-step pipeline: identity and uppercase, first step in pipeline need to be a vector even if it may be simple keyword"
    (is (= "KYIV"
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data "Kyiv"
               :result {:commando/from [:data] :=> [[:identity] :uppercase]}})
            [:instruction :result]))))

  (testing "Three-step pipeline: get-in -> fn -> fn"
    (is (= 43
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data {:nested {:value 42}}
               :result {:commando/from [:data] :=> [[:get-in [:nested :value]] [:fn inc]]}})
            [:instruction :result]))))

  (testing "Pipeline with select-keys then get"
    (is (= "John"
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data {:name "John" :age 30 :secret "x"}
               :result {:commando/from [:data] :=> [[:select-keys [:name :age]] [:get :name]]}})
            [:instruction :result]))))

  (testing "Pipeline with string keys (JSON-compatible)"
    (is (= "KYIV"
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {"data" {"city" "Kyiv"}
               "result" {"commando-from" ["data"] "=>" [["get" "city"] "uppercase"]}})
            [:instruction "result"]))))

  (testing "Single-step pipeline behaves like regular driver"
    (is (= "Kyiv"
          (get-in
            (commando/execute {:commando/from cmds/command-from-spec}
              {:data {:city "Kyiv"}
               :result {:commando/from [:data] :=> [[:get :city]]}})
            [:instruction :result])))))
