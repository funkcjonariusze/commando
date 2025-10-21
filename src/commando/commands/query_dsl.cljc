(ns commando.commands.query-dsl
  (:require
   [commando.core            :as commando]
   [commando.impl.status-map :as smap]
   [commando.impl.utils      :as commando-utils]
   [malli.core               :as malli]
   [malli.error]
   #?(:clj [clojure.pprint :as pprint])))

(def ^:private exception-message-header (str commando-utils/exception-message-header "QueryDSL. "))

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

(deftype ^:private Resolver [resolver_type resolver_data]
  Object
  (toString ^String [_]
    (str "#<Resolver> "
      (case resolver_type
        :instruction-qe (let [{:keys [default-value _instruction]} resolver_data]
                          (pr-str {:type :instruction-qe :default default-value}))
        :instruction (let [{:keys [default-value _instruction]} resolver_data]
                       (pr-str {:type :instruction :default default-value}))
        :fn (let [{:keys [default-value _fn-resolver]} resolver_data]
              (pr-str {:type :fn :default default-value}))
        (throw (ex-info "#<Resolver> Exception. Undefinied resolver type" {:resolver_type resolver_type}))))))

#?(:clj
   (do (defmethod print-method Resolver [obj ^java.io.Writer writer] (.write writer (pr-str (.toString obj))))
       (defmethod pprint/simple-dispatch Resolver [obj] (print-method obj *out*))))

(defn resolve-instruction-qe
  "Take a default-value and Instruction with `:commando/resolve` command
   on the top-level, and return Resolver object that will be processed
   by `->query-run`

   Example
     (resolve-instruction-qe
       []
       {:commando/resolve :cars-by-model
        :model \"Citroen\"}


   See Also
     `commando.commands.query-dsl/->query-run`
     `commando.commands.query-dsl/->resolve-instruction`
         - the same but for any Instruction can execute your registry.
     `commando.commands.query-dsl/->resolve-fn`
         - the same but for simple function resolving"
  [default-value InstructionWithQueryExpression]
  (->Resolver :instruction-qe {:default-value default-value :instruction InstructionWithQueryExpression}))

(defn resolve-instruction
  "Take a default-value and Instruction that can be executed by commando
   registry. Return Resolver object that will be processed by `->query-run`.

   Example
     (resolve-instruction
       0
       {:vector1 [1 2 3]
        :vector2 [3 2 1]
        :result {:commando/fn (fn [& [v1 v2]] (reduce + (map * v1 v2)))
                 :args [{:commando/from [:vector1]}
                        {:commando/from [:vector2]}]}})

   See Also
        `commando.commands.query-dsl/->query-run`
        `commando.commands.query-dsl/->resolve-instruction-qe`
            - the same but for Instruction with `:commando/resolve` command
            on the top-level.
        `commando.commands.query-dsl/->resolve-fn`
            - the same but for simple function resolving"
  [default-value Instruction]
  (->Resolver :instruction {:default-value default-value :instruction Instruction}))

(defn resolve-fn
  "Take a default-value and fn-resolver - simple function that
   can optionally accept KeyProperties(passed data from QueryExpression syntax) map
   and return the data that can be queried by QueryExpression syntax
   by commando registry. Return Resolver object that will be processed
   by `->query-run`.

   Example
     (resolve-fn
       []
       (fn [{:keys [x]}]
        (vec (for [i (range (or x 5))]
               {:value i}))))

   See Also
    `commando.commands.query-dsl/->query-run`
    `commando.commands.query-dsl/->resolve-instruction-qe`
       - the same but for Instruction with `:commando/resolve` command
       on the top-level.
    `commando.commands.query-dsl/->resolve-instruction`
       - the same but for any Instruction can execute your registry."
  [default-value fn-resolver]
  (->Resolver :fn {:default-value default-value :fn-resolver fn-resolver}))
(defn resolver?
  "Check is the obj a `commando.commands.query_dsl/Resolver` instance"
  [obj] (instance? Resolver obj))

(defn ^:private run-resolve-instruction-qe [resolver_data QueryExpression QueryExpressionKeyProperties]
  (let [{:keys [_default-value instruction]} resolver_data
        patch-query (fn [instruction]
                      (cond-> instruction
                        (contains? instruction :commando/resolve ) (assoc :QueryExpression QueryExpression)
                        (contains? instruction "commando-resolve") (assoc "QueryExpression" QueryExpression)
                        QueryExpressionKeyProperties (merge QueryExpressionKeyProperties)))
        result (commando/execute
                 (commando-utils/command-map-spec-registry)
                 (cond
                   (map? instruction) (patch-query instruction)
                   (vector? instruction) (mapv patch-query instruction)
                   :else (throw (ex-info (str exception-message-header "Unsupported structure for InstructionWithQueryExpression resolving functionality") {:instruction-qe instruction}))))]
    (if (= :ok (:status result)) (:instruction result) result)))

(defn ^:private run-resolve-instruction [resolver_data KeyProperties]
  (let [{:keys [_default-value instruction]} resolver_data
        patch-query (fn [instruction]
                      (cond-> instruction
                        KeyProperties (merge KeyProperties)))
        result (commando/execute
                 (commando-utils/command-map-spec-registry)
                 (cond
                   (map? instruction) (patch-query instruction)
                   (vector? instruction) (mapv patch-query instruction)
                   :else (throw (ex-info (str exception-message-header "Unsupported structure for Instruction resolving functionality") {:instruction instruction}))))]
    (if (= :ok (:status result)) (:instruction result) result)))

(defn ^:private run-resolve-fn [resolver_data KeyProperties]
  (let [{:keys [_default-value fn-resolver]} resolver_data
        result (commando/execute
                 (commando-utils/command-map-spec-registry)
                 (fn-resolver KeyProperties))]
    (if (= :ok (:status result)) (:instruction result) result)))

(defn ^:private trying-to-resolve [resolver QueryExpression QueryExpressionKeyProperties ->query-run internal-keys]
  (if (resolver? resolver)
    (let [resolver_type (.-resolver_type resolver)
          resolver_data (.-resolver_data resolver)]
      (case resolver_type
        ;; :instruction-qe not need to loop with ->query-run to obtain the next QueryExpression values
        ;; cause if the are another QueryExpression instruction inside, it has ->query-run by the end of it
        :instruction-qe
        (run-resolve-instruction-qe resolver_data QueryExpression QueryExpressionKeyProperties)
        :instruction
        (->query-run
          (run-resolve-instruction resolver_data QueryExpressionKeyProperties)
          internal-keys)
        :fn
        (->query-run
          (try
            (run-resolve-fn resolver_data QueryExpressionKeyProperties)
            (catch Exception e
              (->
                (smap/status-map-pure)
                (smap/status-map-handle-error {:message (str exception-message-header "resolve-fn. Finished return exception")
                                               :error (commando-utils/serialize-exception e)}))))
          internal-keys)))
    resolver))

(defn ^:private trying-to-value [maybe-resolver]
  (if (resolver? maybe-resolver)
    (:default-value (.-resolver_data maybe-resolver))
    maybe-resolver))

(defn ->query-run
  [m QueryExpression]
  (let [{:keys [expression-keys expression-values expression-props]} (QueryExpression->expand-first QueryExpression)]
    (cond

      (and (map? m) (commando/failed? m)) m

      (map? m)
      (reduce (fn [acc k]
                (if (contains? m k)
                  ;; QE
                  ;; [{[:key {:some-key-properties nil}]
                  ;;   [:internal-key-1
                  ;;    :internal-key-1]}]
                  (let [key-properties (get expression-props k)
                        internal-keys (get expression-values k)]
                    (assoc acc k
                      (if internal-keys
                        ;; QE
                        ;; [{:key
                        ;;   [:internal-key-1
                        ;;    :internal-key-1]}]
                        (let [data (get m k)]
                          (cond
                            (map? data) (->query-run data internal-keys)
                            (coll? data) (mapv #(trying-to-resolve % internal-keys key-properties ->query-run internal-keys) data)
                            (resolver? data) (trying-to-resolve data internal-keys key-properties ->query-run internal-keys)
                            :else data))
                        ;; QE
                        ;; [:key]
                        (let [data (get m k)]
                          (cond
                            (map? data) (trying-to-value data)
                            (coll? data) (mapv trying-to-value data)
                            (resolver? data) (trying-to-value data)
                            :else data)))))
                  (assoc acc
                    k
                    {:status :failed
                     :errors [{:message (str exception-message-header
                                          "QueryExpression. Attribute '" k "' is unreachable.")}]})))
        {}
        expression-keys)

      (coll? m)
      (mapv (fn [e] (->query-run e QueryExpression)) m)

      :else m)))

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
            :passport (resolve-instruction-qe
                        \"- no passport - \"
                        {:commando/resolve :query-passport
                         :first-name \"Adam\"
                         :last-name \"Nowak\"})
            :password (resolve-instruction
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
     `commando.commands.query-dsl/resolve-instruction-qe` run internal call of `commando/execute`.
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
            \"passport\" (resolve-instruction-qe
                           \"- no passport - \"
                           {\"commando-resolve\" \"query-passport\"
                            \"first-name\" \"Adam\"
                            \"last-name\" \"Nowak\"})
            \"password\" (resolve-instruction
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
     `commando.commands.query-dsl/resolve-instruction-qe` run internal call of `commando/execute`.
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

(comment

  (require 'commando.commands.builtin)
  (def registry
    (commando/create-registry
      [command-resolve-spec
       commando.commands.builtin/command-fn-spec
       commando.commands.builtin/command-from-spec
       commando.commands.builtin/command-apply-spec]))

  (defn execute-with-registry [instruction]
    (:instruction
     (commando.core/execute
       registry
       instruction)))

  (defmethod command-resolve :test-instruction-qe [_ {:keys [x QueryExpression]}]
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

           :resolve-fn (resolve-fn "default value for resolve-fn"
                         (fn [{:keys [x]}]
                           (let [y (or x 1)
                                 range-y (if (< 10 y) 10 y)]
                             (for [z (range 0 range-y)]
                               {:a
                                {:b {:c (+ y z)}
                                 :d {:c (inc (+ y z))
                                     :f (inc (inc (+ y z)))}}}))))

           :resolve-fn-error (resolve-fn "default value for resolve-fn"
                               (fn [{:keys [_x]}]
                                 (throw (ex-info "Exception" {:error "no reason"}))))

           :resolve-fn-call (for [x (range 10)]
                              (resolve-fn (str "default value for resolve-fn-call -> " x)
                                (fn [properties]
                                  (let [x (or (:x properties) x)]
                                    {:a {:b x}}))))

           :resolve-instruction (resolve-instruction "default value for resolve-instruction"
                                  {:commando/fn (fn [count-elements]
                                                  (vec
                                                    (for [x (range 0 count-elements)]
                                                      {:a
                                                       {:b {:c x}
                                                        :d {:c (inc x)
                                                            :f (inc (inc x))}}})))
                                   :args [2]})

           :resolve-instruction-with-error (resolve-instruction "default value for resolve-instruction-with-error"
                                             {:commando/fn (fn [& _body]
                                                             (throw (ex-info "Exception" {:error "no reason"})))
                                              :args []})

           :resolve-instruction-qe (resolve-instruction-qe "default value for resolve-instruction-qe"
                                     {:commando/resolve :test-instruction-qe
                                      :x 1})}
        (->query-run QueryExpression))))

  ;; ----------------
  ;; Simple attribute
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :x 1
     :QueryExpression
     [:string]})

  ;; --------
  ;; Defaults
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :x 1
     :QueryExpression
     [:string
      :map
      :coll
      :resolve-fn
      :resolve-instruction
      :resolve-instruction-qe]})

  ;; ----------------------------
  ;; Resolving data Map and Colls
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :x 20
     :QueryExpression
     [:string
      {:map
       [{:a
         [:b]}]}
      {:coll
       [{:a
         [:b]}]}]})

  ;; ----------
  ;; resolve-fn
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :x 20
     :QueryExpression
     [{:resolve-fn
       [{:a
         [:b]}]}]})


  ;; resolve-fn with overriding `:x`
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :x 20
     :QueryExpression
     [{[:resolve-fn {:x 1000}]
       [{:a
         [:b]}]}]})

  ;; ---------------------------
  ;; resolver packed in sequence
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :QueryExpression
     [{:resolve-fn-call
       [{:a
         [:b]}]}]})

  ;; resolver packed in sequence with overrding
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :QueryExpression
     [{[:resolve-fn-call {:x 100}]
       [{:a
         [:b]}]}]})

  ;; -------------------
  ;; resolve-instruction
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :x 20
     :QueryExpression
     [{:resolve-instruction
       [{:a
         [:b]}]}]})

  ;; resolve-instruction with overrding `:args`
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :x 20
     :QueryExpression
     [{[:resolve-instruction
        {:args [100]}]
       [{:a
         [:b]}]}]})

  ;; ---------------------
  ;; resolve-instruction-qe
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :x 20
     :QueryExpression
     [{:resolve-instruction-qe
       [{:map [{:a [:b]}]}]}]})

  ;; loop resolve-instruction-qe with override `:x`
  (execute-with-registry
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
                    [:b]}]}]}]}]}]})

  ;; -----------------------------
  ;; ERRORS inside the instruction

  ;; 1. Key `:EEE` is not existing
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :x 20
     :QueryExpression
     [:EEE
      {:resolve-fn
       [{:a
         [:b
          :EEE]}]}]})

  ;; 2. Internal instruction return an error
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :x 20
     :QueryExpression
     [{:resolve-instruction-with-error
       [{:a [:b]}]}]})

  ;; 3. Cause the resolve-fn is an simple function
  ;;   call, so there are handled in try..catch and
  ;;   return as a status-map exception either.
  (execute-with-registry
    {:commando/resolve :test-instruction-qe
     :x 20
     :QueryExpression
     [{:resolve-fn-error
       [:a]}]})

  )
