(ns commando.commands.query-dsl
  (:require
   [commando.core       :as commando]
   [commando.impl.utils :as commando-utils]
   [malli.core          :as malli]
   [malli.error]
   #?(:clj [clojure.pprint :as pprint])))

(def ^:private exception-message-header (str commando-utils/exception-message-header "Graph Query. "))

(defn ^:private conj-dup-error
  [coll obj]
  (if (reduce (fn [_ obj-in-coll] (when (= obj-in-coll obj) (reduced obj))) nil coll)
    (throw (ex-info (str
                      exception-message-header
                      "Duplicated key inside QueryExpression structrure: "
                      obj)
                    {}))
    (conj coll obj)))

(def ^:private QueryExpressionKeyMalli (malli/deref [:or :keyword :string]))
(def ^:private QueryExpressionMalli
  (malli/deref
    [:schema
     {:registry
      {::QueryExpression
       [:vector {:error/message "QueryExpression syntax exception"}
        [:or
         QueryExpressionKeyMalli
         [:cat QueryExpressionKeyMalli :map]
         [:map-of
          [:or
           QueryExpressionKeyMalli
           [:cat QueryExpressionKeyMalli :map]]
          [:ref ::QueryExpression]]]]}}
     ::QueryExpression]))

(defn ^:private QueryExpression->expand-first
  "Just imagine the EQL that has been lobotomyd
   that exactly what it is

  (QueryExpression->expand-first
     [:a0
      [:b0 {:b0props {}}]
      {:c0
       [:a1
        :b1
        {:c1
         [:a2
          :b2]}]}
      {[:d0 {:d0props {}}]
       [:a1
        :b1]}])
   =>
   {:expression-keys [:a0 :b0 :c0 :d0]
    :expression-values {:a0 nil
                        :b0 nil
                        :c0 [:a1 :b1 {:c1 [:a2 :b2]}]
                        :d0 [:a1 :b1]}
    :expression-props  {:a0 nil
                        :b0 {:b0props {}}
                        :c0 nil
                        :d0 {:d0props {}}}}

   or the same with strings:

   (QueryExpression->expand-first
      [\"a0\"
       [\"b0\" {\"b0props\" {}}]
       {\"c0\"
        [\"a1\"
         \"b1\"
         {\"c1\"
          [\"a2\"
           \"b2\"]}]}
       {[\"d0\" {\"d0props\" {}}]
        [\"a1\"
         \"b1\"]}])
   =>
   {:expression-keys [\"a0\" \"b0\" \"c0\" \"d0\"],
    :expression-values {\"a0\" nil,
                        \"b0\" nil,
                        \"c0\" [\"a1\" \"b1\" {\"c1\" [\"a2\" \"b2\"]}],
                        \"d0\" [\"a1\" \"b1\"]},
    :expression-props  {\"a0\" nil,
                        \"b0\" {\"b0props\" {}},
                        \"c0\" nil,
                        \"d0\" {\"d0props\" {}}}}

   See
    `QueryExpressionMalli`
"
  [QueryExpression]
  (reduce (fn [acc k]
            (cond
              (map? k) (let [[k v] (first k)]
                         (if (coll? k)
                           (let [[k props] k]
                             (-> acc
                                 (update :expression-keys conj-dup-error k)
                                 (update :expression-values assoc k v)
                                 (update :expression-props assoc k props)))
                           (-> acc
                               (update :expression-keys conj-dup-error k)
                               (update :expression-values assoc k v)
                               (update :expression-props assoc k nil))))
              (coll? k) (let [[k props] k]
                          (-> acc
                              (update :expression-keys conj-dup-error k)
                              (update :expression-values assoc k nil)
                              (update :expression-props assoc k props)))
              :else (-> acc
                        (update :expression-keys conj-dup-error k)
                        (update :expression-values assoc k nil)
                        (update :expression-props assoc k nil))))
          {:expression-keys []
           :expression-values {}
           :expression-props {}}
          QueryExpression))

(deftype ^:private QueryResolve [value Instruction]
  Object
  (toString ^String [_]
    (str "#<QueryResolve>%s"
      (pr-str {:default value
               :instruction Instruction}))))

#?(:clj
   (do (defmethod print-method QueryResolve [obj ^java.io.Writer writer] (.write writer (pr-str (.toString obj))))
       (defmethod pprint/simple-dispatch QueryResolve [obj] (print-method obj *out*))))


(defn query-resolve? [obj] (instance? QueryResolve obj))
(defn query-resolve
  "Take `default-value`, `Instruction`, and if QueryExpression ask for property, then
   execute `Instruction`(internal ivoke of commanod/execute) and return the result,
   otherwise return `default-value`

   [:value-covered-by-query-resolve] <- return `default-value`
   [{:value-covered-by-query-resolve <- execute `Instruction` and lookup for `:SOME-VALUES`
     [:SOME-VALUES]}]"
  [default-value Instruction] (new QueryResolve default-value Instruction))
(defn ^:private resolve-execute
  [data QueryExpression QueryExpressionKeyProperties]
  (if (query-resolve? data)
    (let [patch-query (fn [query-resolve-instruction]
                        (cond-> query-resolve-instruction
                          true (assoc :QueryExpression QueryExpression)
                          true (assoc "QueryExpression" QueryExpression)
                          QueryExpressionKeyProperties (merge QueryExpressionKeyProperties)))
          result (commando/execute
                  (commando.impl.utils/command-map-spec-registry)
                  (let [internal-instruction-to-execute (.-Instruction data)]
                    (cond
                      (map? internal-instruction-to-execute) (patch-query internal-instruction-to-execute)
                      (vector? internal-instruction-to-execute) (mapv patch-query internal-instruction-to-execute)
                      :else (throw (ex-info (str exception-message-header "Unsupported structure for internal resolving functionality") {:part internal-instruction-to-execute})))))]
      (if (= :ok (:status result)) (:instruction result) result))
    data))
(defn ^:private return-values [data] (if (query-resolve? data) (.-value data) data))

(defn ->query-run
  [m QueryExpression]
  (let [{:keys [expression-keys expression-values expression-props]} (QueryExpression->expand-first QueryExpression)]
    (reduce (fn [acc k]
              (if (contains? m k)
                (let [key-properties (get expression-props k)
                      internal-keys (get expression-values k)]
                  (if internal-keys
                    (assoc acc
                           k
                           (let [data (get m k)]
                             (cond
                               (map? data) (->query-run data internal-keys)
                               (coll? data) (mapv #(-> %
                                                    (resolve-execute internal-keys key-properties)
                                                    (->query-run internal-keys)) data)
                               :else (resolve-execute data internal-keys key-properties))))
                    (assoc acc
                           k
                           (let [data (get m k)]
                             (cond
                               (map? data) data
                               (coll? data) (mapv return-values data)
                               :else (return-values data))))))
                (assoc acc
                       k
                       {:status :failed
                        :errors [{:message (str exception-message-header
                                             "QueryExpression attribute '" k "' is unreachable")}]})))
            {}
            expression-keys)))

(defn ->>query-run [QueryExpression m] (->query-run m QueryExpression))

(defmulti command-resolve (fn [tx-type _data] tx-type))
(defmethod command-resolve :default
  [undefinied-tx-type _]
  (throw (ex-info (str exception-message-header
                    "Unedefinied command-resolve '" undefinied-tx-type "'")
                  {:resolver/tx undefinied-tx-type})))

(def ^{:doc "
  Description
    command-resolve-spec - behave like command-mutation-spec
    but allow invoking `commando/execute` internally inside the
    evaluation step, what make it usefull for querying data.

    Querying(internal execution) controlled by QueryExpression - custom
    DSL, what visually mention the EQL.

  QueryExpression Syntax Example
   [:a0
    [:b0 {:b0props {}}]
    {:c0
     [:a1
      :b1
      {:c1
       [:a2
        :b2]}]}
    {[:d0 {:d0props {}}]
     [:a1
      :b1]}]

   Example
     (defmethod commando.commands.builtin/command-mutation :generate-password [_ _]
       {:random-string (apply str (repeatedly 20 #(rand-nth \"abcdefghijklmnopqrstuvwxyz0123456789\")))})

     (defmethod command-resolve :query-passport [_ {:keys [first-name last-name QueryExpression]}]
       ;; Mocking SQL operation to db
       (when (and
               (= first-name \"Adam\")
               (= last-name \"Nowak\"))
         (->query-run
           {:number \"FA939393\"
            :issued \"10-04-2020\"}
           QueryExpression)))

     (defmethod command-resolve :query-user [_ {:keys [QueryExpression]}]
       (-> {:first-name \"Adam\"
            :last-name \"Nowak\"
            :info {:age 25 :weight 70 :height 188}
            :passport (query-resolve
                        \"- no passport - \"
                        {:commando/resolve :query-passport
                         :first-name \"Adam\"
                         :last-name \"Nowak\"})
            :password (query-resolve
                        \"- no password - \"
                        {:commando/mutation :generate-password})}
         (->query-run QueryExpression)))


     ;; Let try to use it!
     (:instruction
       (commando.core/execute
         [commands-builtin/command-mutation-spec
          command-resolve-spec]
         {:commando/resolve :query-user
          :QueryExpression
          [:first-name
           {:info
            [:age
             :weight]}]}))
      => {:first-name \"Adam\"
          :info {:age 25, :weight 70}}

      ;; do the same but with different QueryExpression

     [:first-name
      :password]
      => {:first-name \"Adam\", :password \"- no password - \"}

     [:first-name
      {:password []}]
      => {:first-name \"Adam\", :password {:random-string \"lexccpux2pzdupzwx79o\"}}

     [:first-name
      {:passport
       [:number]}]
      => {:first-name \"Adam\", :password {:number \"FA939393\"}}

     [:first-name
      :UNEXISTING]
      => {:first-name \"Adam\",
          :UNEXISTING {:status :failed, :errors [{:message \"Commando. Graph Query. QueryExpression attribute ':UNEXISTING' is unreachable\"}]}}

   Parts
     `commando.commands.query-dsl/query-resolve` run internal call of `commando/execute`.
     `commando.commands.query-dsl/->query-run` trim query data according to passed QueryExpression
     `commando.commands.query-dsl/command-resolve` multimethod to declare resolvers.

   See Also
     `commando.core/execute`
     `commando.commands.query-dsl/command-mutation-spec`
     `commando.commands.builtin/command-mutation-spec`"}
  command-resolve-spec
  {:type :commando/resolve
   :recognize-fn #(and (map? %) (contains? % :commando/resolve))
   :validate-params-fn (fn [m]
                         (if-let [explain-m
                                  (malli.error/humanize
                                    (malli/explain
                                      [:map
                                       [:commando/resolve :keyword]
                                       [:QueryExpression
                                        {:optional true}
                                        QueryExpressionMalli]]
                                      m))]
                           explain-m
                           true))
   :apply (fn [_instruction _command-map m] (command-resolve (:commando/resolve m) (dissoc m :commando/resolve)))
   :dependencies {:mode :all-inside}})

(def ^{:doc "
  Description
    command-resolve-json-spec - like command-resolve-spec but
    use \"string\" keys instead of Keywords

    Querying(internal execution) controlled by QueryExpression - custom
    DSL, what visually mention the EQL.

  QueryExpression syntax example
    [\"a0\"
     [\"b0\" {\"b0props\" {}}]
     {\"c0\"
      [\"a1\"
       \"b1\"
       {\"c1\"
        [\"a2\"
         \"b2\"]}]}
     {[\"d0\" {\"d0props\" {}}]
      [\"a1\"
       \"b1\"]}]

   Example
     (defmethod commando.commands.builtin/command-mutation \"generate-password\" [_ _]
       {\"random-string\" (apply str (repeatedly 20 #(rand-nth \"abcdefghijklmnopqrstuvwxyz0123456789\")))})

     (defmethod command-resolve \"query-passport\" [_ {:strs [first-name last-name QueryExpression]}]
       ;; Mocking SQL operation to db
       (when (and
               (= first-name \"Adam\")
               (= last-name \"Nowak\"))
         (->query-run
           {\"number\" \"FA939393\"
            \"issued\" \"10-04-2020\"}
           QueryExpression)))

     (defmethod command-resolve \"query-user\" [_ {:strs [QueryExpression]}]
       (-> {\"first-name\" \"Adam\"
            \"last-name\" \"Nowak\"
            \"info\" {\"age\" 25 \"weight\" 70 \"height\" 188}
            \"passport\" (query-resolve
                        \"- no passport - \"
                        {\"commando-resolve\" \"query-passport\"
                         \"first-name\" \"Adam\"
                         \"last-name\" \"Nowak\"})
            \"password\" (query-resolve
                        \"- no password - \"
                        {\"commando-mutation\" \"generate-password\"})}
         (->query-run QueryExpression)))


     ;; Let try to use it!
     (:instruction
      (commando.core/execute
        [commands-builtin/command-mutation-json-spec
         command-resolve-json-spec]
        {\"commando-resolve\" \"query-user\"
         \"QueryExpression\"
         [\"first-name\"
          {\"info\"
           [\"age\"
            \"weight\"]}]}))
     => {\"first-name\" \"Adam\",
         \"info\" {\"age\" 25, \"weight\" 70}}

      ;; do the same but with different QueryExpression

     {\"commando-resolve\" \"query-user\"
      \"QueryExpression\"
      [\"first-name\"
       {\"password\" []}]}
      => {\"first-name\" \"Adam\",
          \"password\" {\"random-string\" \"zz0fydanqzwd2cjyu7yc\"}}

     {\"commando-resolve\" \"query-user\"
      \"QueryExpression\"
      [\"first-name\"
       {\"passport\"
        [\"number\"]}]}
      => {\"first-name\" \"Adam\", \"passport\" {}}

     {\"commando-resolve\" \"query-user\"
      \"QueryExpression\"
      [\"first-name\"
       \"UNEXISTING\"]}
      => {\"first-name\" \"Adam\",
          \"UNEXISTING\" {:status :failed,
                          :errors [{:message \"Commando. Graph Query. QueryExpression attribute 'UNEXISTING' is unreachable\"}]}}

   Parts
     `commando.commands.query-dsl/query-resolve` run internal call of `commando/execute`.
     `commando.commands.query-dsl/->query-run` trim query data according to passed QueryExpression
     `commando.commands.query-dsl/command-resolve` multimethod to declare resolvers.

   See Also
     `commando.core/execute`
     `commando.commands.query-dsl/command-mutation-spec`
     `commando.commands.builtin/command-mutation-spec`"}
  command-resolve-json-spec
  {:type :commando/resolve-json
   :recognize-fn #(and (map? %) (contains? % "commando-resolve"))
   :validate-params-fn (fn [m]
                         (if-let [explain-m
                                  (malli.error/humanize
                                    (malli/explain
                                      [:map
                                       ["commando-resolve" [:string {:min 1}]]
                                       ["QueryExpression"
                                        {:optional true}
                                        QueryExpressionMalli]]
                                      m))]
                           explain-m
                           true))
   :apply (fn [_instruction _command-map m]
               (command-resolve (get m "commando-resolve") (dissoc m "commando-resolve")))
   :dependencies {:mode :all-inside}})

