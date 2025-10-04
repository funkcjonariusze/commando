(ns commando.commands.builtin
  (:require
   [commando.impl.dependency :as deps]
   [commando.impl.utils :as utils]
   [malli.core :as malli]
   [malli.error]))

(def command-fn-spec
  {:type :commando/fn
   :recognize-fn #(and (map? %) (contains? % :commando/fn))
   :validate-params-fn (fn [m]
                         (if-let [m-explain
                                  (malli.error/humanize
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

(def command-apply-spec
  {:type :commando/apply
   :recognize-fn #(and (map? %) (contains? % :commando/apply))
   :validate-params-fn (fn [m]
                         (if-let [m-explain
                                  (malli.error/humanize
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


(def command-from-spec
  {:type :commando/from
   :recognize-fn #(and (map? %) (contains? % :commando/from))
   :validate-params-fn (fn [m]
                         (if-let [m-explain
                                  (malli.error/humanize
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

(def command-from-json-spec
  {:type :commando/from-json
   :recognize-fn #(and (map? %) (contains? % "commando-from"))
   :validate-params-fn (fn [m]
                         (if-let [m-explain
                                  (malli.error/humanize
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

;; in mutation underscore what is occur in example
;; understand why command-from-spec
;; Fix examples in registry 
;;
;; CommandRegistry - wymyślić sproszonę wytlumaczenie. Basics - lepsze wyjaśnienia, dla tego co i jak się wykonuje

(def command-mutation-spec
  {:type :commando/mutation
   :recognize-fn #(and (map? %) (contains? % :commando/mutation))
   :validate-params-fn (fn [m]
                         (if-let [m-explain (malli.error/humanize
                                              (malli/explain [:map [:commando/mutation :keyword]] m))]
                           m-explain
                           true))
   :apply (fn [_instruction _command-map m]
            (let [m-tx-type (:commando/mutation m) m (dissoc m :commando/mutation)]
              (command-mutation m-tx-type m)))
   :dependencies {:mode :all-inside}})

(def command-mutation-json-spec
  {:type :commando/mutation-json
   :recognize-fn #(and (map? %) (contains? % "commando-mutation"))
   :validate-params-fn (fn [m]
                         (if-let [m-explain (malli.error/humanize
                                              (malli/explain [:map ["commando-mutation" [:string {:min 1}]]] m))]
                           m-explain
                           true))
   :apply (fn [_instruction _command-map m]
            (let [m-tx-type (get m "commando-mutation") m (dissoc m "commando-mutation")] (command-mutation m-tx-type m)))
   :dependencies {:mode :all-inside}})



