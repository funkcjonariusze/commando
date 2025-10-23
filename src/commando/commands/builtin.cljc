(ns commando.commands.builtin
  (:require
   [commando.core            :as commando]
   [commando.impl.dependency :as deps]
   [commando.impl.utils      :as utils]
   [malli.core               :as malli]
   [malli.error              :as malli-error]))

;; ======================
;; Fn
;; ======================

(def
  ^{:doc "
  Description
    command-fn-spec - execute `:commando/fn` function/symbol/keyword
    with arguments passed inside `:args` key.

  Example
   (:instruction
    (commando/execute
      [command-fn-spec]
      {:commando/fn #'clojure.core/+
       :args [1 2 3]}))
   ;; => 6

   (:instruction
    (commando/execute
      [command-fn-spec]
      {:commando/fn (fn [& [a1 a2 a3]]
                      (* a1 a2 a3))
       :args [1 2 3]}))
   ;; => 6

   See Also
     `commando.core/execute`
     `commando.commands.builtin/command-apply-spec`"}
  command-fn-spec
  {:type :commando/fn
   :recognize-fn #(and (map? %) (contains? % :commando/fn))
   :validate-params-fn (fn [m]
                         (if-let [m-explain
                                  (malli-error/humanize
                                    (malli/explain
                                      [:map
                                       [:commando/fn utils/ResolvableFn]
                                       [:args {:optional true} coll?]]
                                      m))]
                           m-explain
                           true))
   :apply (fn [_instruction _command-map m]
            (let [m-fn (utils/resolve-fn (:commando/fn m))
                  m-args (:args m [])
                  result (apply m-fn m-args)] result))
   :dependencies {:mode :all-inside}})

;; ======================
;; Apply
;; ======================

(def ^{:doc "
  Description
    command-apply-spec - Apply `:=` function/symbol/keyword
    to value passed inside `:commando/apply`

  Example
    (:instruction
      (commando/execute
        [command-apply-spec]
        {:commando/apply {:value 10}
         := :value}))
     ;; => 10

   See Also
     `commando.core/execute`
     `commando.commands.builtin/command-fn-spec`"}
  command-apply-spec
  {:type :commando/apply
   :recognize-fn #(and (map? %) (contains? % :commando/apply))
   :validate-params-fn (fn [m]
                         (if-let [m-explain
                                  (malli-error/humanize
                                    (malli/explain
                                      [:map [:commando/apply :any]
                                       [:= utils/ResolvableFn]] m))]
                           m-explain
                           true))
   :apply (fn [_instruction _command-path-obj command-map]
            (let [result (:commando/apply command-map)
                  result (let [m-= (utils/resolve-fn (:= command-map))] (if m-= (m-= result) result))]
              result))
   :dependencies {:mode :all-inside}})

;; ======================
;; From
;; ======================

(def
  ^{:doc "
  Description
    command-fn-spec - get value from another command or existing value
    in Instruction. Path to another command is passed inside `:commando/from`
    key, optionally you can apply `:=` function/symbol/keyword to the result.

    Path can be sequence of keywords, strings or integers, starting absolutely from
    the root of Instruction, or relatively from the current command position by
    using \"../\" and \"./\" strings in paths.

    [:some 2 \"value\"] - absolute path, started from the root key :some
    [\"../\" 2 \"value\"] - relative path, go up one level and then down to [2 \"value\"]

  Example
    (:instruction
      (commando/execute [command-fn-spec command-from-spec]
        {\"value\"
         {:commando/fn (fn [& values] (apply + values))
          :args [1 2 3]}
         \"value-incremented\"
         {:commando/from [\"value\"] := inc}}))
      => {\"value\" 6, \"value-incremented\" 7}

    (:instruction
      (commando/execute [command-from-spec]
        {:a {:value 1
             :result {:commando/from [\"../\" :value]}}
         :b {:value 2
             :result {:commando/from [\"../\" :value]}}}))
      => {:a {:value 1, :result 1}, :b {:value 2, :result 2}}

   See Also
     `commando.core/execute`
     `commando.commands.builtin/command-fn-spec`
     `commando.commands.builtin/command-from-spec`"}
  command-from-spec
  {:type :commando/from
   :recognize-fn #(and (map? %) (contains? % :commando/from))
   :validate-params-fn (fn [m]
                         (if-let [m-explain
                                  (malli-error/humanize
                                    (malli/explain [:map
                                                    [:commando/from
                                                     [:sequential {:error/message "commando/from should be a sequence path to value in Instruction: [:some 2 \"value\"]"}
                                                      [:or :string :keyword :int]]]
                                                    [:= {:optional true} [:or utils/ResolvableFn :string]]]
                                      m))]
                           m-explain
                           true))
   :apply (fn [instruction command-path-obj command-map]
            (let [path-to-another-command (deps/point-target-path instruction command-path-obj)
                  result (get-in instruction path-to-another-command)
                  result (let [m-= (:= command-map)]
                           (if m-= (if (string? m-=)
                                     (get result m-=)
                                     (let [m-= (utils/resolve-fn m-=)]
                                       (m-= result))) result))]
              result))
   :dependencies {:mode :point
                  :point-key :commando/from}})

(def ^{:doc "
  Description
    command-fn-json-spec - get value from another command or existing value
    in Instruction. Path to another command is passed inside `\"commando-from\"`
    key, optionally you can get value of object by using `\"=\"` key.

    Path can be sequence of keywords, strings or integers, starting absolutely from
    the root of Instruction, or relatively from the current command position by
    using \"../\" and \"./\" strings in paths.

    [\"some\" 2 \"value\"] - absolute path, started from the root key \"some\"
    [\"../\" 2 \"value\"] - relative path, go up one level and then down to [2 \"value\"]

  Example
    (:instruction
     (commando/execute [command-from-json-spec]
       {\"a\" {\"value\" {\"container\" 1}
            \"result\" {\"commando-from\" [\"../\" \"value\"] \"=\" \"container\"}}
        \"b\" {\"value\" {\"container\" 2}
            \"result\" {\"commando-from\" [\"../\" \"value\"] \"=\" \"container\"}}}))

    {\"a\" {\"value\" {\"container\" 1}, \"result\" 1},
     \"b\" {\"value\" {\"container\" 2}, \"result\" 2}}

   See Also
     `commando.core/execute`
     `commando.commands.builtin/command-fn-spec`"}
  command-from-json-spec
  {:type :commando/from-json
   :recognize-fn #(and (map? %) (contains? % "commando-from"))
   :validate-params-fn (fn [m]
                         (if-let [m-explain
                                  (malli-error/humanize
                                    (malli/explain [:map
                                                    ["commando-from"
                                                     [:sequential {:error/message "commando-from should be a sequence path to value in Instruction: [\"some\" 2 \"value\"]"}
                                                      [:or :string :int]]]
                                                    ["=" {:optional true} [:string {:min 1}]]] m))]
                           m-explain
                           true))
   :apply (fn [instruction command-path-obj command-map]
            (let [path-to-another-command (deps/point-target-path instruction command-path-obj)
                  result (get-in instruction path-to-another-command)
                  result (if-let [m-= (get command-map "=")]
                           (if (string? m-=)
                             (get result m-=)
                             result)
                           result)]
              result))
   :dependencies {:mode :point
                  :point-key "commando-from"}})

;; ======================
;; Mutation
;; ======================

(defmulti command-mutation (fn [tx-type _data] tx-type))
(defmethod command-mutation :default
  [undefined-tx-type _]
  (throw (ex-info (str utils/exception-message-header
                    "command-mutation. Undefined "
                    undefined-tx-type
                    " type '"
                    undefined-tx-type
                    "'")
           {:commando/mutation undefined-tx-type})))

(def ^{:doc "
  Description
    command-mutation-spec - execute mutation of Instruction data.
    Mutation type is passed inside `:commando/mutation` key and arguments
    to mutation passed inside rest of map.

    To declare mutation create method of `command-mutation` multimethod

  Example
    (defmethod commando.commands.builtin/command-mutation :generate-string [_ {:keys [lenght]}]
      {:random-string (apply str (repeatedly (or lenght 10) #(rand-nth \"abcdefghijklmnopqrstuvwxyz0123456789\")))})

    (defmethod commando.commands.builtin/command-mutation :generate-number [_ {:keys [from to]}]
      {:random-number (let [bound (- to from)] (+ from (rand-int bound)))})

    (:instruction
     (commando/execute
       [command-mutation-spec]
       {:a {:commando/mutation :generate-number :from 10 :to 20}
        :b {:commando/mutation :generate-string :lenght 5}}))
     => {:a {:random-number 14}, :b {:random-string \"5a379\"}}

   See Also
     `commando.core/execute`
     `commando.commands.builtin/command-mutation-spec`
     `commando.commands.builtin/command-mutation`"}
  command-mutation-spec
  {:type :commando/mutation
   :recognize-fn #(and (map? %) (contains? % :commando/mutation))
   :validate-params-fn (fn [m]
                         (if-let [m-explain (malli-error/humanize
                                              (malli/explain [:map [:commando/mutation :keyword]] m))]
                           m-explain
                           true))
   :apply (fn [_instruction _command-map m]
            (let [m-tx-type (:commando/mutation m) m (dissoc m :commando/mutation)]
              (command-mutation m-tx-type m)))
   :dependencies {:mode :all-inside}})

(def ^{:doc "
  Description
    command-mutation-json-spec - execute mutation of Instruction data.
    Mutation type is passed inside `\"commando-mutation\"` key and arguments
    to mutation passed inside rest of map.

    To declare mutation create method of `command-mutation` multimethod

  Example
    (defmethod commando.commands.builtin/command-mutation \"generate-string\" [_ {:strs [lenght]}]
      {\"random-string\" (apply str (repeatedly (or lenght 10) #(rand-nth \"abcdefghijklmnopqrstuvwxyz0123456789\")))})

    (defmethod commando.commands.builtin/command-mutation \"generate-number\" [_ {:strs [from to]}]
      {\"random-number\" (let [bound (- to from)] (+ from (rand-int bound)))})

    (:instruction
     (commando/execute
       [command-mutation-json-spec]
       {\"a\" {\"commando-mutation\" \"generate-number\" \"from\" 10 \"to\" 20}
        \"b\" {\"commando-mutation\" \"generate-string\" \"lenght\" 5}}))
      => {\"a\" {\"random-number\" 18}, \"b\" {\"random-string\" \"m3gj1\"}}

   See Also
     `commando.core/execute`
     `commando.commands.builtin/command-mutation-spec`
     `commando.commands.builtin/command-mutation-json-spec`
     `commando.commands.builtin/command-mutation`"}
  command-mutation-json-spec
  {:type :commando/mutation-json
   :recognize-fn #(and (map? %) (contains? % "commando-mutation"))
   :validate-params-fn (fn [m]
                         (if-let [m-explain (malli-error/humanize
                                              (malli/explain [:map ["commando-mutation" [:string {:min 1}]]] m))]
                           m-explain
                           true))
   :apply (fn [_instruction _command-map m]
            (let [m-tx-type (get m "commando-mutation") m (dissoc m "commando-mutation")] (command-mutation m-tx-type m)))
   :dependencies {:mode :all-inside}})


;; ======================
;; Macro
;; ======================

(defmulti command-macro (fn [tx-type _data] tx-type))
(defmethod command-macro :default
  [undefinied-tx-type _]
  (throw (ex-info
           (str utils/exception-message-header
             "command-macro. Undefinied '" undefinied-tx-type "'")
           {:commando/macro undefinied-tx-type})))

(def ^{:doc "
  Description
    command-macro-spec - help to define reusable instruction template,
    what execute instruction using the same registry as the current one.
    To declare macro expand `command-mutation` multimethod.

  Example
    Asume we have two vectors with string numbers:
     1) [\"1\", \"2\", \"3\"], 2) [\"4\" \"5\" \"6\"]
    we need to parse them to integers and then calculate dot product.
    Here the solution using commando commands with one instruction

    (:instruction
     (commando/execute
       [command-fn-spec command-from-spec command-apply-spec]
       {:= :dot-product
        :commando/apply
        {:vector1-str [\"1\" \"2\" \"3\"]
         :vector2-str [\"4\" \"5\" \"6\"]
         ;; -------
         ;; Parsing
         :vector1
         {:commando/fn (fn [str-vec]
                         (mapv #(Integer/parseInt %) str-vec))
          :args [{:commando/from [\"../\" \"../\" \"../\" :vector1-str]}]}
         :vector2
         {:commando/fn (fn [str-vec]
                         (mapv #(Integer/parseInt %) str-vec))
          :args [{:commando/from [\"../\" \"../\" \"../\" :vector2-str]}]}
         ;; -----------
         ;; Dot Product
         :dot-product
         {:commando/fn (fn [& [v1 v2]] (reduce + (map * v1 v2)))
          :args [{:commando/from [\"../\" \"../\" \"../\" :vector1]}
                 {:commando/from [\"../\" \"../\" \"../\" :vector2]}]}}}))
      => 32

    But what if we need to calculate those instruction many times with different vectors?
    It means we need to repeat the same instruction many times with different input data,
    what quickly enlarges our Instruction size(every usage) and makes it unreadable.

    To solve this problem we can use `command-macro` to define reusable instruction template

    (defmethod command-macro :vector-dot-product [_macro-type {:keys [vector1-str vector2-str]}]
      {:= :dot-product
       :commando/apply
       {:vector1-str [\"1\" \"2\" \"3\"]
        :vector2-str [\"4\" \"5\" \"6\"]
        ;; -------
        ;; Parsing
        :vector1
        {:commando/fn (fn [str-vec]
                        (mapv #(Integer/parseInt %) str-vec))
         :args [{:commando/from [\"../\" \"../\" \"../\" :vector1-str]}]}
        :vector2
        {:commando/fn (fn [str-vec]
                        (mapv #(Integer/parseInt %) str-vec))
         :args [{:commando/from [\"../\" \"../\" \"../\" :vector2-str]}]}
        ;; -----------
        ;; Dot Product
        :dot-product
        {:commando/fn (fn [& [v1 v2]] (reduce + (map * v1 v2)))
         :args [{:commando/from [\"../\" \"../\" \"../\" :vector1]}
                {:commando/from [\"../\" \"../\" \"../\" :vector2]}]}}})

    and then just use it

    (:instruction
      (commando/execute
        [command-macro-spec command-fn-spec command-from-spec command-apply-spec]
        {:vector-dot-1
         {:commando/macro :vector-dot-product
          :vector1-str [\"1\" \"2\" \"3\"]
          :vector2-str [\"4\" \"5\" \"6\"]}
         :vector-dot-2
         {:commando/macro :vector-dot-product
          :vector1-str [\"10\" \"20\" \"30\"]
          :vector2-str [\"4\" \"5\" \"6\"]}}))
     => {:vector-dot-1 32, :vector-dot-2 32}

  See Also
     `commando.core/execute`
     `commando.commands.builtin/command-macro`"}
  command-macro-spec
  {:type :commando/macro
   :recognize-fn #(and
                    (map? %)
                    (contains? % :commando/macro))
   :validate-params-fn (fn [m]
                         (if-let [explain-m
                                  (malli-error/humanize
                                    (malli/explain
                                      [:map
                                       [:commando/macro {:optional true} :keyword]]
                                      m))]
                           explain-m
                           true))
   :apply (fn [_instruction _command-map m]
            (let [macro-type (get m :commando/macro)
                  macro-data (dissoc m :commando/macro)
                  result (commando/execute
                           (utils/command-map-spec-registry)
                           (command-macro macro-type macro-data))]
              (if (= :ok (:status result))
                (:instruction result)
                (throw (ex-info (str utils/exception-message-header "command-macro. Failure execution :commando/macro") result)))))
   :dependencies {:mode :all-inside}})

(def ^{:doc "
  Description
    command-macro-json-spec - help to define reusable instruction template,
    what execute instruction using the same registry as the current one.
    To declare macro expand `command-mutation` multimethod. Using string
    key \"commando-macro\" for declaring macroses instead of keyword :commando/macro.

  Example
    read one from `command-macro-spec`.

  See Also
     `commando.core/execute`
     `commando.commands.builtin/command-macro`
     `commando.commands.builtin/command-macro-spec`"}
  command-macro-json-spec
  {:type :commando/macro
   :recognize-fn #(and
                    (map? %)
                    (contains? % "commando-macro"))
   :validate-params-fn (fn [m]
                         (if-let [explain-m
                                  (malli-error/humanize
                                    (malli/explain
                                      [:map
                                       ["commando-macro" {:optional true} :string]]
                                      m))]
                           explain-m
                           true))
   :apply (fn [_instruction _command-map m]
            (let [macro-type (get m "commando-macro")
                  macro-data (dissoc m "commando-macro")
                  result (commando/execute
                           (utils/command-map-spec-registry)
                           (command-macro macro-type macro-data))]
              (if (= :ok (:status result))
                (:instruction result)
                (throw (ex-info (str utils/exception-message-header "command-macro. Failure execution :commando/macro") result)))))
   :dependencies {:mode :all-inside}})

