(ns commando.commands.query-dsl-test
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [commando.commands.builtin   :as cmds-builtin]
   [commando.commands.query-dsl :as cmds-query-dsl]
   [commando.core               :as commando]))


(def ^:private db
  {:permissions
   [{:id 1
     :permission-name "add-doc"
     :options [{:type "pdf" :preview true}, {:type "rtf" :preview false }, {:type "odt" :preview false}]}
    {:id 2
     :permission-name "remove-doc"
     :options [{:type "pdf" :preview true}, {:type "rtf" :preview false}, {:type "odt" :preview false}]}
    {:id 3
     :permission-name "none"
     :options []}]
   :users
   [{:id 1
     :name "Adam West"
     :email "adam@west.com"
     :password "12345678"
     :permissions ["add-doc" "remove-doc"]}
    {:id 2
     :name "Peter Griffin"
     :email "peter@mail.com"
     :password "impeter88"
     :permissions ["add-doc"]}
    {:id 3
     :name "Lois Griffin"
     :email "lois@yahoo.net"
     :password "imlois77"
     :permission ["remove-doc"]}]})

(defn get-permissions-by-name [permission-name]
  (first (filter (fn [x] (= permission-name (:permission-name x))) (:permissions db))))

(defn get-user-by-email [email]
  (first (filter (fn [x] (= email (:email x))) (:users db))))

(defmethod cmds-query-dsl/command-resolve :query-permission
  [_ {:keys [permission-name QueryExpression]}]
  (-> (get-permissions-by-name permission-name)
    (cmds-query-dsl/->query-run QueryExpression)))
(defmethod cmds-query-dsl/command-resolve :query-user
  [_ {:keys [email QueryExpression]}]
  (let [user-info (get-user-by-email email)]
    (when user-info
      (cmds-query-dsl/->>query-run
       QueryExpression
       {:id (:id user-info)
        :name (:name user-info)
        :email (:email user-info)
        :password (clojure.string/replace (:password user-info) #"." "*")
        :permissions (cmds-query-dsl/query-resolve
                      (:permissions user-info)
                      (mapv
                       (fn [permission-name]
                         {:commando/resolve :query-permission
                          :permission-name permission-name})
                       (:permissions user-info)))}))))

(deftest black-box-test-query

  (testing "Querying data from resolver"
    (is
      (=
        {:id 2,
         :name "Peter Griffin",
         :email "peter@mail.com",
         :password "*********",
         :permissions ["add-doc"]}
        (:instruction
         (commando.core/execute
           [commando.commands.query-dsl/command-resolve-spec]
           {:commando/resolve :query-user
            :email "peter@mail.com"
            :QueryExpression
            [:id
             :name
             :email
             :password
             :permissions]})))))

  (testing "Querying and Resolving data"
    (is
      (=
        {:id 2,
         :name "Peter Griffin",
         :email "peter@mail.com",
         :password "*********",
         :permissions
         [{:permission-name "add-doc",
           :options [{:type "pdf"} {:type "rtf"} {:type "odt"}]}]}
        (:instruction
         (commando.core/execute
           [commando.commands.query-dsl/command-resolve-spec]
           {:commando/resolve :query-user
            :email "peter@mail.com"
            :QueryExpression
            [:id
             :name
             :email
             :password
             {:permissions
              [:permission-name
               {:options
                [:type]}]}]})))))

  (testing "Querying unexising-data"
    (is
      (=
        {:id 2,
         :name "Peter Griffin"
         :UNEXISTING-FIELD {:status :failed, :errors [{:message "Commando. Graph Query. QueryExpression attribute ':UNEXISTING-FIELD' is unreachable"}]}}
        (:instruction
         (commando.core/execute
           [commando.commands.query-dsl/command-resolve-spec]
           {:commando/resolve :query-user
            :email "peter@mail.com"
            :QueryExpression
            [:id
             :name
             :UNEXISTING-FIELD]})))))

  (testing "Overriding EQL data while quering"
    (is
      (=
        {:id 2,
         :name "Peter Griffin",
         :email "peter@mail.com",
         :password "*********",
         :permissions [{:id 3, :permission-name "none", :options []}]}
        (:instruction
         (commando.core/execute
           [commando.commands.query-dsl/command-resolve-spec]
           {:commando/resolve :query-user
            :email "peter@mail.com"
            :QueryExpression
            [:id
             :name
             :email
             :password
             {[:permissions {:permission-name "none"}]
              [:id
               :permission-name
               :options]}]}))))))

