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

(def ^:private -schema:command-fn
  [:map
   [:commando/fn utils/ResolvableFn]
   [:args {:optional true} coll?]
   [:=> {:optional true} utils/malli:driver-spec]
   ["=>" {:optional true} utils/malli:driver-spec]])

(def ^:private -validator:command-fn  (malli/validator -schema:command-fn))
(def ^:private -explainer:command-fn  (malli/explainer -schema:command-fn))

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
                         (if (-validator:command-fn m)
                           true
                           (malli-error/humanize (-explainer:command-fn m))))
   :apply (fn [_instruction _command-map m]
            (let [m-fn (utils/resolve-fn (:commando/fn m))
                  m-args (:args m [])
                  result (apply m-fn m-args)] result))
   :dependencies {:mode :all-inside}})

;; ======================
;; Apply
;; ======================

(def ^:private -schema:command-apply
  [:map
   [:commando/apply :any]
   [:=> {:optional true} utils/malli:driver-spec]
   ["=>" {:optional true} utils/malli:driver-spec]])

(def ^:private -validator:command-apply  (malli/validator -schema:command-apply))
(def ^:private -explainer:command-apply  (malli/explainer -schema:command-apply))

(def ^{:doc "
  Description
    command-apply-spec - Returns value of `:commando/apply`.
    Use `:=>` driver to post-process the result.

  Example
    (:instruction
      (commando/execute
        [command-apply-spec]
        {:commando/apply {:value 10}
         :=> [:get :value]}))
     ;; => 10

   See Also
     `commando.core/execute`
     `commando.commands.builtin/command-fn-spec`"}
  command-apply-spec
  {:type :commando/apply
   :recognize-fn #(and (map? %) (contains? % :commando/apply))
   :validate-params-fn (fn [m]
                         (if (-validator:command-apply m)
                           true
                           (malli-error/humanize (-explainer:command-apply m))))
   :apply (fn [_instruction _command-path-obj command-map]
            (:commando/apply command-map))
   :dependencies {:mode :all-inside}})

;; ======================
;; From
;; ======================

(def ^:private -malli:commando-from-path
  (malli/deref
    [:sequential {:error/message "commando/from should be a sequence path to value in Instruction: [:some 2 \"value\"]"}
     [:or :string :keyword :int]]))

(def ^:private -schema:command-from-kw
  [:map
   [:commando/from -malli:commando-from-path]
   [:=> {:optional true} utils/malli:driver-spec]
   ["=>" {:optional true} utils/malli:driver-spec]])

(def ^:private -validator:command-from-kw  (malli/validator -schema:command-from-kw))
(def ^:private -explainer:command-from-kw  (malli/explainer -schema:command-from-kw))

(def ^:private -schema:command-from-str
  [:map
   ["commando-from" -malli:commando-from-path]
   [:=> {:optional true} utils/malli:driver-spec]
   ["=>" {:optional true} utils/malli:driver-spec]])

(def ^:private -validator:command-from-str  (malli/validator -schema:command-from-str))
(def ^:private -explainer:command-from-str  (malli/explainer -schema:command-from-str))

(def
  ^{:doc "
  Description
    command-from-spec - get value from another command or existing value
    in Instruction. Path to another command is passed inside `:commando/from`
    key, optionally you can apply `:=>` driver to the result.

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
         {:commando/from [\"value\"] :=> [:fn inc]}}))
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
            \"result\" {\"commando-from\" [\"../\" \"value\"] \"=>\" [\"get\" \"container\"]}}
        \"b\" {\"value\" {\"container\" 2}
            \"result\" {\"commando-from\" [\"../\" \"value\"] \"=>\" [\"get\" \"container\"]}}}))

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
                                 (and
                                   (contains? m :commando/from)
                                   (contains? m "commando-from"))
                                 "The keyword :commando/from and the string \"commando-from\" cannot be used simultaneously in one command."

                                 (contains? m :commando/from)
                                 (when-not (-validator:command-from-kw m)
                                   (malli-error/humanize (-explainer:command-from-kw m)))

                                 (contains? m "commando-from")
                                 (when-not (-validator:command-from-str m)
                                   (malli-error/humanize (-explainer:command-from-str m))))]
                           (if m-explain
                             m-explain
                             true)))
   :apply (fn [instruction command-path-obj _command-map]
            (let [path-to-another-command (deps/point-target-path instruction command-path-obj)]
              (get-in instruction path-to-another-command)))
   :dependencies {:mode :point
                  :point-key [:commando/from
                              "commando-from"]}})

;; ======================
;; Context
;; ======================

(def ^:private -schema:command-context-kw
  [:map
   [:commando/context [:sequential {:error/message "commando/context should be a sequential path: [:some :key]"}
                       [:or :string :keyword :int]]]
   [:=> {:optional true} utils/malli:driver-spec]
   ["=>" {:optional true} utils/malli:driver-spec]])

(def ^:private -validator:command-context-kw  (malli/validator -schema:command-context-kw))
(def ^:private -explainer:command-context-kw  (malli/explainer -schema:command-context-kw))

(def ^:private -schema:command-context-str
  [:map
   ["commando-context" [:sequential {:error/message "commando-context should be a sequential path: [\"some\" \"key\"]"}
                        [:or :string :keyword :int]]]
   [:=> {:optional true} utils/malli:driver-spec]
   ["=>" {:optional true} utils/malli:driver-spec]])

(def ^:private -validator:command-context-str  (malli/validator -schema:command-context-str))
(def ^:private -explainer:command-context-str  (malli/explainer -schema:command-context-str))

(defn command-context-spec
  "Creates a CommandMapSpec that resolves references to external context data
   captured via closure. Context is immutable per registry and resolves before
   other commands (dependency mode :none).

   ctx — a map with arbitrary structure to look up values from.

   Instruction usage (keyword keys):
     {:commando/context [:path :to :data]}
     {:commando/context [:path :to :data] :=> [:get :key]}
     {:commando/context [:path :to :data] :default 0}

   Instruction usage (string keys, JSON-compatible):
     {\"commando-context\" [\"path\" \"to\" \"data\"]}
     {\"commando-context\" [\"path\" \"to\" \"data\"] \"=>\" [\"get\" \"key\"]}
     {\"commando-context\" [\"path\" \"to\" \"data\"] \"default\" 0}

   Parameters:
     :commando/context — sequential path for get-in on ctx
     :=> — optional driver for post-processing [:get :key], [:fn inc], etc.
     :default — optional fallback value when path is not found in ctx.
                 Without :default, missing path throws an error.

   Example:
     (def my-ctx {:rates {:vat 0.20} :codes {\"01\" \"Kyiv\"}})

     (:instruction
      (commando/execute
        [(command-context-spec my-ctx)
         command-from-spec command-fn-spec]
        {:vat     {:commando/context [:rates :vat]}
         :city    {:commando/context [:codes \"01\"]}
         :missing {:commando/context [:nonexistent] :default \"N/A\"}
         :total   {:commando/fn * :args [{:commando/from [:vat]} 1000]}}))
     ;; => {:vat 0.20 :city \"Kyiv\" :missing \"N/A\" :total 200.0}

   See Also
     `commando.core/execute`
     `commando.commands.builtin/command-from-spec`"
  [ctx]
  {:pre [(map? ctx)]}
  {:type :commando/context
   :recognize-fn #(and (map? %)
                    (or
                      (contains? % :commando/context)
                      (contains? % "commando-context")))
   :validate-params-fn
   (fn [m]
     (let [m-explain
           (cond
             (and (contains? m :commando/context) (contains? m "commando-context"))
             "The keyword :commando/context and the string \"commando-context\" cannot be used simultaneously in one command."
             (contains? m :commando/context)
             (when-not (-validator:command-context-kw m)
               (malli-error/humanize (-explainer:command-context-kw m)))
             (contains? m "commando-context")
             (when-not (-validator:command-context-str m)
               (malli-error/humanize (-explainer:command-context-str m))))]
       (if m-explain m-explain true)))
   :apply
   (fn [_instruction _command-path-obj command-map]
     (let [path (or (get command-map :commando/context) (get command-map "commando-context"))]
       (get-in ctx path nil)))
   :dependencies {:mode :none}})

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

(def ^:private -schema:command-mutation-kw
  [:map [:commando/mutation [:or :keyword :string]]
   [:=> {:optional true} utils/malli:driver-spec]
   ["=>" {:optional true} utils/malli:driver-spec]])

(def ^:private -validator:command-mutation-kw  (malli/validator -schema:command-mutation-kw))
(def ^:private -explainer:command-mutation-kw  (malli/explainer -schema:command-mutation-kw))

(def ^:private -schema:command-mutation-str
  [:map ["commando-mutation" [:or :keyword :string]]
   [:=> {:optional true} utils/malli:driver-spec]
   ["=>" {:optional true} utils/malli:driver-spec]])

(def ^:private -validator:command-mutation-str  (malli/validator -schema:command-mutation-str))
(def ^:private -explainer:command-mutation-str  (malli/explainer -schema:command-mutation-str))

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
                                 (and
                                   (contains? m :commando/mutation)
                                   (contains? m "commando-mutation"))
                                 "The keyword :commando/mutation and the string \"commando-mutation\" cannot be used simultaneously in one command."
                                 (contains? m :commando/mutation)
                                 (when-not (-validator:command-mutation-kw m)
                                   (malli-error/humanize (-explainer:command-mutation-kw m)))
                                 (contains? m "commando-mutation")
                                 (when-not (-validator:command-mutation-str m)
                                   (malli-error/humanize (-explainer:command-mutation-str m))))]
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

(def ^:private -schema:command-macro-kw
  [:map
   [:commando/macro [:or :keyword :string]]
   [:=> {:optional true} utils/malli:driver-spec]
   ["=>" {:optional true} utils/malli:driver-spec]])

(def ^:private -validator:command-macro-kw  (malli/validator -schema:command-macro-kw))
(def ^:private -explainer:command-macro-kw  (malli/explainer -schema:command-macro-kw))

(def ^:private -schema:command-macro-str
  [:map
   ["commando-macro" [:or :keyword :string]]
   [:=> {:optional true} utils/malli:driver-spec]
   ["=>" {:optional true} utils/malli:driver-spec]])

(def ^:private -validator:command-macro-str  (malli/validator -schema:command-macro-str))
(def ^:private -explainer:command-macro-str  (malli/explainer -schema:command-macro-str))

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
       {:=> [:get :dot-product]
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
      {:=> [:get :dot-product]
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
                                 (and
                                   (contains? m :commando/macro)
                                   (contains? m "commando-macro"))
                                 "The keyword :commando/macro and the string \"commando-macro\" cannot be used simultaneously in one command."
                                 (contains? m :commando/macro)
                                 (when-not (-validator:command-macro-kw m)
                                   (malli-error/humanize (-explainer:command-macro-kw m)))
                                 (contains? m "commando-macro")
                                 (when-not (-validator:command-macro-str m)
                                   (malli-error/humanize (-explainer:command-macro-str m))))]
                           (if m-explain
                             m-explain
                             true)))
   :apply (fn [_instruction _command-map m]
            (let [[macro-type macro-data]
                  (cond
                    (contains? m :commando/macro) [(get m :commando/macro) (dissoc m :commando/macro)]
                    (contains? m "commando-macro") [(get m "commando-macro") (dissoc m "commando-macro")])
                  result (commando/execute
                           (utils/command-map-spec-registry)
                           (command-macro macro-type macro-data))]
              (if (= :ok (:status result))
                (:instruction result)
                (throw (ex-info (str utils/exception-message-header "command-macro. Failure execution :commando/macro") result)))))
   :dependencies {:mode :all-inside}})

