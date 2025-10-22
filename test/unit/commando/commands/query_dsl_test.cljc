(ns commando.commands.query-dsl-test
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [clojure.string              :as string]
   [commando.commands.builtin   :as command-builtin]
   [commando.commands.query-dsl :as command-query-dsl]
   [commando.impl.utils         :as commando-utils]
   [commando.core               :as commando]
   [commando.test-helpers       :as helpers]))

(def ^:private db
  {:permissions
   [{:id 1
     :permission-name "add-doc"
     :options [{:type "pdf" :preview true}, {:type "rtf" :preview false}, {:type "odt" :preview false}]}
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
     :permissions ["remove-doc"]}]})

(def registry
  (commando/create-registry
   [command-query-dsl/command-resolve-spec
    command-builtin/command-fn-spec
    command-builtin/command-from-spec
    command-builtin/command-apply-spec]))

(defn get-permissions-by-name [permission-name]
  (first (filter (fn [x] (= permission-name (:permission-name x))) (:permissions db))))

(defn get-user-by-email [email]
  (first (filter (fn [x] (= email (:email x))) (:users db))))

(defmethod command-query-dsl/command-resolve :query-permission
  [_ {:keys [permission-name QueryExpression]}]
  (-> (get-permissions-by-name permission-name)
      (command-query-dsl/->query-run QueryExpression)))

(defmethod command-query-dsl/command-resolve :query-user
  [_ {:keys [email QueryExpression]}]
  (let [user-info (get-user-by-email email)]
    (when user-info
      (command-query-dsl/->>query-run
       QueryExpression
       {:id          (:id user-info)
        :name        (:name user-info)
        :email       (:email user-info)
        :password    (string/replace (:password user-info) #"." "*")
        :permissions (mapv
                      (fn [permission-name]
                        (command-query-dsl/resolve-instruction-qe
                         permission-name
                         {:commando/resolve :query-permission
                          :permission-name  permission-name}))
                      (:permissions user-info))}))))

(deftest black-box-test-query
  (testing "Testing query on mock-data (permissions and users)"
    (is
     (=
      {:id 2,
       :name "Peter Griffin",
       :email "peter@mail.com",
       :password "*********",
       :permissions ["add-doc"]}
      (:instruction
       (commando.core/execute
        registry
        {:commando/resolve :query-user ;; resolver
         :email "peter@mail.com"
         :QueryExpression
         [:id
          :name
          :email
          :password
          :permissions]})))
     "Wrong resolving user and permisssions.")

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
        registry
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
             [:type]}]}]})))
     "Test user query with nested and joined permission data. Should return only needed value :type from nested map")

    (is
     (=
      {:id 2,
       :name "Peter Griffin"
       :UNEXISTING-FIELD {:status :failed,
                          :errors [{:message "Commando. QueryDSL. QueryExpression. Attribute ':UNEXISTING-FIELD' is unreachable."}]}}
      (:instruction
       (commando.core/execute
        registry
        {:commando/resolve :query-user
         :email "peter@mail.com"
         :QueryExpression
         [:id
          :name
          :UNEXISTING-FIELD]})))
     "Test query for a non-existent attribute on a user. The resolver should return an error map for the unreachable field.")

    (is
     (=
      {:id 3,
       :name "Lois Griffin",
       :email "lois@yahoo.net",
       :password "********",
       :user-role
       {:status :failed,
        :errors
        [{:message
          "Commando. QueryDSL. QueryExpression. Attribute ':user-role' is unreachable."}]}}
      (:instruction
       (commando.core/execute
        registry
        {:commando/resolve :query-user
         :email "lois@yahoo.net"
         :QueryExpression
         [:id
          :name
          :email
          :password
          {:user-role
           [:id
            :permission-name]}]})))
     "Test user with a mismatched :user-role key in the DB. The resolver should return error.")

    (is
     (=
      {:id 2,
       :name "Peter Griffin",
       :email "peter@mail.com",
       :password "*********",
       :permissions [{:id 3, :permission-name "none", :options []}]}
      (:instruction
       (commando.core/execute
        registry
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
            :options]}]})))
     "Test parameterized query. This EQL query should override the default join logic and query for a specific permission, even one the user does not have.")

    (is
     (=
      {:id 3
       :permission-name "none"
       :options []}
      (:instruction
       (commando.core/execute
        registry
        {:commando/resolve :query-permission
         :permission-name "none"
         :QueryExpression
         [:id
          :permission-name
          :options]})))
     "Test direct query for a permission that has an empty nested list (:options).")

    (is
     (nil?
      (:instruction
       (commando.core/execute
        registry
        {:commando/resolve :query-user
         :email "nonexistent@user.com"
         :QueryExpression
         [:id :name]})))
     "Test query for a non-existent user. Result should be nil.")))

(defmethod command-query-dsl/command-resolve :test-instruction-qe [_ {:keys [x QueryExpression]}]
  (let [x (or x 10)]
    (-> {:string "Value"

         :map {:a
               {:b {:c x}
                :d {:c (inc x)
                    :f (inc (inc x))}}}

         :coll [{:a
                 {:b {:c x}
                  :d {:c (inc x)
                      :f (inc (inc x))}}}
                {:a
                 {:b {:c x}
                  :d {:c (dec x)
                      :f (dec (dec x))}}}]

         :resolve-fn       (command-query-dsl/resolve-fn
                            "default value for resolve-fn"
                            (fn [{:keys [x]}]
                              (let [y (or x 1)
                                    range-y (if (< 10 y) 10 y)]
                                (for [z (range 0 range-y)]
                                  {:a
                                   {:b {:c (+ y z)}
                                    :d {:c (inc (+ y z))
                                        :f (inc (inc (+ y z)))}}}))))

         :resolve-fn-error (command-query-dsl/resolve-fn
                            "default value for resolve-fn"
                            (fn [{:keys [_x]}]
                              (throw (ex-info "Exception" {:error "no reason"}))))

         :coll-resolve-fn (for [x (range 10)]
                            (command-query-dsl/resolve-fn
                             "default value for resolve-fn-call"
                             (fn [properties]
                               (let [x (or (:x properties) x)]
                                 {:a {:b x}}))))

         :resolve-instruction (command-query-dsl/resolve-instruction
                               "default value for resolve-instruction"
                               {:commando/fn (fn [count-elements]
                                               (vec
                                                (for [x (range 0 count-elements)]
                                                  {:a
                                                   {:b {:c x}
                                                    :d {:c (inc x)
                                                        :f (inc (inc x))}}})))
                                :args [2]})

         :resolve-instruction-with-error (command-query-dsl/resolve-instruction
                                          "default value for resolve-instruction-with-error"
                                          {:commando/fn (fn [& _body]
                                                          (throw (ex-info "Exception" {:error "no reason"})))
                                           :args []})

         :resolve-instruction-qe         (command-query-dsl/resolve-instruction-qe
                                          "default value for resolve-instruction-qe"
                                          {:commando/resolve :test-instruction-qe
                                           :x 1})}
        (command-query-dsl/->query-run QueryExpression))))

(deftest query-expression-test
  (testing "Succesfull execution"
    (is
     (=
      {:string "Value"}
      (:instruction
       (commando/execute
        registry
        {:commando/resolve :test-instruction-qe
         :x 1
         :QueryExpression
         [:string]})))
     "Returns a single attribute :string for query.")

    (is
     (=
      {:string "Value",
       :map {:a {:b {:c 1}, :d {:c 2, :f 3}}},
       :coll [{:a {:b {:c 1}, :d {:c 2, :f 3}}}
              {:a {:b {:c 1}, :d {:c 0, :f -1}}}],
       :resolve-fn             "default value for resolve-fn",
       :resolve-instruction    "default value for resolve-instruction",
       :resolve-instruction-qe "default value for resolve-instruction-qe"}
      (:instruction
       (commando/execute
        registry
        {:commando/resolve :test-instruction-qe
         :x 1
         :QueryExpression
         [;; simple data
          :string
          :map
          :coll
          ;; data from resolvers
          :resolve-fn
          :resolve-instruction
          :resolve-instruction-qe]})))
     "Returns defaults for queried data.")

    (is
     (=
      {:string "Value",
       :map {:a {:b {:c 20}}},
       :coll [{:a {:b {:c 20}}} {:a {:b {:c 20}}}]}
      (:instruction
       (commando/execute
        registry
        {:commando/resolve :test-instruction-qe
         :x 20
         :QueryExpression
         [:string
          {:map
           [{:a
             [:b]}]}
          {:coll
           [{:a
             [:b]}]}]})))
     "Returns nested data by non-resolver data types.")

    (is
     (=
      {:resolve-fn [{:a {:b {:c 1}}}]}
      (:instruction
       (commando/execute
        registry
        {:commando/resolve :test-instruction-qe
         :x 20
         :QueryExpression
         [{:resolve-fn
           [{:a
             [:b]}]}]})))
     "Return data for resolver. The resolving procedure for 'resolve-fn'")

    (is
     (=
      {:resolve-fn
       [{:a {:b {:c 1000}}}
        {:a {:b {:c 1001}}}
        {:a {:b {:c 1002}}}
        {:a {:b {:c 1003}}}
        {:a {:b {:c 1004}}}
        {:a {:b {:c 1005}}}
        {:a {:b {:c 1006}}}
        {:a {:b {:c 1007}}}
        {:a {:b {:c 1008}}}
        {:a {:b {:c 1009}}}]}
      (:instruction
       (commando/execute
        registry
        {:commando/resolve :test-instruction-qe
         :x 20
         :QueryExpression
         [{[:resolve-fn {:x 1000}]
           [{:a
             [:b]}]}]})))
     "Return data for resolver and overriding it params. Resolving procedure for 'resolve-fn'")

    (is
     (=
      {:coll-resolve-fn
       [{:a {:b 100}}
        {:a {:b 100}}
        {:a {:b 100}}
        {:a {:b 100}}
        {:a {:b 100}}
        {:a {:b 100}}
        {:a {:b 100}}
        {:a {:b 100}}
        {:a {:b 100}}
        {:a {:b 100}}]}
      (:instruction
       (commando/execute
        registry
        {:commando/resolve :test-instruction-qe
         :QueryExpression
         [{[:coll-resolve-fn {:x 100}]
           [{:a
             [:b]}]}]}))))

    (is
     (=
      {:resolve-instruction
       [{:a {:b {:c 0}}}
        {:a {:b {:c 1}}}]}
      (:instruction
       (commando/execute
        registry
        {:commando/resolve :test-instruction-qe
         :x 20
         :QueryExpression
         [{:resolve-instruction
           [{:a
             [:b]}]}]}))))

    (is
     (=
      {:resolve-instruction
       [{:a {:b {:c 0}}}
        {:a {:b {:c 1}}}
        {:a {:b {:c 2}}}
        {:a {:b {:c 3}}}
        {:a {:b {:c 4}}}]}
      (:instruction
       (commando/execute
        registry
        {:commando/resolve :test-instruction-qe
         :x 20
         :QueryExpression
         [{[:resolve-instruction
            {:args [5]}]
           [{:a
             [:b]}]}]}))))

    (is
     (=
      {:resolve-instruction-qe {:map {:a {:b {:c 1}}}}}
      (:instruction
       (commando/execute
        registry
        {:commando/resolve :test-instruction-qe
         :x 20
         :QueryExpression
         [{:resolve-instruction-qe
           [{:map [{:a [:b]}]}]}]}))))

    (is
     (=
      {:resolve-instruction-qe
       {:map {:a {:b {:c 1}}},
        :resolve-instruction-qe
        {:map {:a {:b {:c 1000}}},
         :resolve-instruction-qe
         {:map {:a {:b {:c 10000000}}}}}}}
      (:instruction
       (commando/execute
        registry
        {:commando/resolve :test-instruction-qe
         :x 1
         :QueryExpression
         [{:resolve-instruction-qe
           [{:map [{:a
                    [:b]}]}
            {[:resolve-instruction-qe {:x 1000}]
             [{:map [{:a
                      [:b]}]}
              {[:resolve-instruction-qe {:x 10000000}]
               [{:map [{:a
                        [:b]}]}]}]}]}]})))))

  (testing "Failing exception"
    (is
     (=
      {:EEE
       {:status :failed,
        :errors
        [{:message
          "Commando. QueryDSL. QueryExpression. Attribute ':EEE' is unreachable."}]},
       :resolve-fn
       [{:a
         {:b {:c 1},
          :EEE
          {:status :failed,
           :errors
           [{:message
             "Commando. QueryDSL. QueryExpression. Attribute ':EEE' is unreachable."}]}}}]}
      (:instruction
       (commando/execute
        registry
        {:commando/resolve :test-instruction-qe
         :x 20
         :QueryExpression
         [:EEE
          {:resolve-fn
           [{:a
             [:b
              :EEE]}]}]}))))

    (is
     (helpers/status-map-contains-error?
      (get-in
       (binding [commando-utils/*debug-mode* true]
         (commando/execute
          registry
          {:commando/resolve :test-instruction-qe
           :x 20
           :QueryExpression
           [{:resolve-fn-error
             [:a]}]}))
       [:instruction :resolve-fn-error])
      (fn [error]
        (=
         {:type "exception-info",
          :message "Exception"
          :cause nil,
          :data {:error "no reason"}}
         (-> error :error helpers/remove-stacktrace (dissoc :class))))))

    (is
     (helpers/status-map-contains-error?
      (get-in
       (binding [commando-utils/*debug-mode* true]
         (commando/execute
          registry
          {:commando/resolve :test-instruction-qe
           :x 20
           :QueryExpression
           [{:resolve-instruction-with-error
             [{:a [:b]}]}]}))
       [:instruction :resolve-instruction-with-error])
      (fn [error]
        (=
         {:type "exception-info",
          :message "Exception"
          :cause nil,
          :data {:error "no reason"}}
         (-> error :error helpers/remove-stacktrace (dissoc :class))))))))

