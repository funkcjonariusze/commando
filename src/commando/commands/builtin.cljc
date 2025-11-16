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

(def ^:private -malli:commando-from-path
  (malli/deref
    [:sequential {:error/message "commando/from should be a sequence path to value in Instruction: [:some 2 \"value\"]"}
     [:or :string :keyword :int]]))

(def
  ^{:doc "
  Description
    command-from-spec - get value from another command or existing value
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

    (:instruction
     (commando/execute [command-from-spec]
       {\"a\" {\"value\" {\"container\" 1}
            \"result\" {\"commando-from\" [\"../\" \"value\"] \"=\" \"container\"}}
        \"b\" {\"value\" {\"container\" 2}
            \"result\" {\"commando-from\" [\"../\" \"value\"] \"=\" \"container\"}}}))

      => /{\"a\" {\"value\" {\"container\" 1}, \"result\" 1},
         / \"b\" {\"value\" {\"container\" 2}, \"result\" 2}} 

   See Also
     `commando.core/execute`
     `commando.commands.builtin/command-fn-spec`
     `commando.commands.builtin/command-from-spec`"}
  command-from-spec
  {:type :commando/from
   :recognize-fn #(and (map? %)
                    (or
                      (contains? % :commando/from)
                      (contains? % "commando-from")))
   :validate-params-fn (fn [m]
                         (let [m-explain
                               (cond
                                 (contains? m :commando/from)
                                 (malli-error/humanize
                                   (malli/explain
                                     [:map
                                      [:commando/from -malli:commando-from-path]
                                      [:= {:optional true} [:or utils/ResolvableFn :string]]]
                                     m))
                                 (contains? m "commando-from")
                                 (malli-error/humanize
                                   (malli/explain
                                     [:map
                                      ["commando-from" -malli:commando-from-path]
                                      ["=" {:optional true} [:string {:min 1}]]]
                                     m)))]
                           (if m-explain
                             m-explain
                             true)))
   :apply (fn [instruction command-path-obj command-map]
            (cond
              (contains? command-map :commando/from)
              (let [path-to-another-command (deps/point-target-path instruction command-path-obj)
                    result (get-in instruction path-to-another-command)
                    result (let [m-= (:= command-map)]
                             (if m-= (if (string? m-=)
                                       (get result m-=)
                                       (let [m-= (utils/resolve-fn m-=)]
                                         (m-= result))) result))]
                result)
              (contains? command-map "commando-from")
              (let [path-to-another-command (deps/point-target-path instruction command-path-obj)
                    result (get-in instruction path-to-another-command)
                    result (if-let [m-= (get command-map "=")]
                             (if (string? m-=)
                               (get result m-=)
                               result)
                             result)]
                result)))
   :dependencies {:mode :point
                  :point-key [:commando/from
                              "commando-from"]}})

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
    Mutation id is passed inside `:commando/mutation` or `\"commando-mutation\"`
    key and arguments to mutation passed inside rest of map.

    To declare mutation create method of `command-mutation` multimethod

  Example
    (defmethod commando.commands.builtin/command-mutation :generate-string [_ {:keys [length]}]
      {:random-string (apply str (repeatedly (or length 10) #(rand-nth \"abcdefghijklmnopqrstuvwxyz0123456789\")))})

    (defmethod commando.commands.builtin/command-mutation :generate-number [_ {:keys [from to]}]
      {:random-number (let [bound (- to from)] (+ from (rand-int bound)))})

    (:instruction
     (commando/execute
       [command-mutation-spec]
       {:a {:commando/mutation :generate-number :from 10 :to 20}
        :b {:commando/mutation :generate-string :length 5}}))
     => {:a {:random-number 14}, :b {:random-string \"5a379\"}}

  Example with-string keys
    (defmethod commando.commands.builtin/command-mutation \"generate-string\" [_ {:strs [length]}]
      {\"random-string\" (apply str (repeatedly (or length 10) #(rand-nth \"abcdefghijklmnopqrstuvwxyz0123456789\")))})

    (defmethod commando.commands.builtin/command-mutation \"generate-number\" [_ {:strs [from to]}]
      {\"random-number\" (let [bound (- to from)] (+ from (rand-int bound)))})

    (:instruction
     (commando/execute
       [command-mutation-spec]
       {\"a\" {\"commando-mutation\" \"generate-number\" \"from\" 10 \"to\" 20}
        \"b\" {\"commando-mutation\" \"generate-string\" \"length\" 5}}))
      => {\"a\" {\"random-number\" 18}, \"b\" {\"random-string\" \"m3gj1\"}}

   See Also
     `commando.core/execute`
     `commando.commands.builtin/command-mutation-spec`
     `commando.commands.builtin/command-mutation`"}
  command-mutation-spec
  {:type :commando/mutation
   :recognize-fn #(and (map? %)
                    (or
                      (contains? % :commando/mutation)
                      (contains? % "commando-mutation")))
   :validate-params-fn (fn [m]
                         (let [m-explain
                               (cond
                                 (contains? m :commando/mutation)
                                 (malli-error/humanize
                                   (malli/explain
                                     [:map [:commando/mutation [:or :keyword :string]]]
                                     m))
                                 (contains? m "commando-mutation")
                                 (malli-error/humanize
                                   (malli/explain
                                     [:map ["commando-mutation" [:or :keyword :string]]]
                                     m)))]
                           (if m-explain
                             m-explain
                             true)))
   :apply (fn [_instruction _command-map m]
            (cond
              (contains? m :commando/mutation)
              (command-mutation (get m :commando/mutation)  (dissoc m :commando/mutation))
              (contains? m "commando-mutation")
              (command-mutation (get m "commando-mutation") (dissoc m "commando-mutation"))))
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
    Macro id is passed inside `:commando/macro` or `\"commando-macro\"`
    key and arguments to mutation passed inside rest of map.

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
       {:vector1-str vector1-str
        :vector2-str vector2-str
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
     => {:vector-dot-1 32, :vector-dot-2 320}

  See Also
     `commando.core/execute`
     `commando.commands.builtin/command-macro`"}
  command-macro-spec
  {:type :commando/macro
   :recognize-fn #(and
                    (map? %)
                    (or
                     (contains? % :commando/macro)
                     (contains? % "commando-macro")))
   :validate-params-fn (fn [m]
                         (let [m-explain
                               (cond
                                 (contains? m :commando/macro)
                                 (malli-error/humanize
                                   (malli/explain
                                     [:map
                                      [:commando/macro [:or :keyword :string]]]
                                     m))
                                 (contains? m "commando-macro")
                                 (malli-error/humanize
                                   (malli/explain
                                     [:map
                                      ["commando-macro" [:or :keyword :string]]]
                                     m)))]
                           (if m-explain
                             m-explain
                             true)))
   :apply (fn [_instruction _command-map m]
            (let [[macro-type macro-data]
                  (cond
                    (get m :commando/macro) [(get m :commando/macro) (dissoc m :commando/macro)]
                    (get m "commando-macro") [(get m "commando-macro") (dissoc m "commando-macro")])
                  result (commando/execute
                           (utils/command-map-spec-registry)
                           (command-macro macro-type macro-data))]
              (if (= :ok (:status result))
                (:instruction result)
                (throw (ex-info (str utils/exception-message-header "command-macro. Failure execution :commando/macro") result)))))
   :dependencies {:mode :all-inside}})

