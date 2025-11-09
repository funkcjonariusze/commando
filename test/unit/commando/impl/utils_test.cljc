(ns commando.impl.utils-test
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [commando.test-helpers     :as helpers]
   [commando.impl.utils :as sut]
   [malli.core :as malli]))

(deftest serialize-exception
  #?(:clj
     (testing "Serialization exception CLJ"
       ;; -----------------
       ;; Simple Exceptions
       ;; -----------------
       (let [e
             (sut/serialize-exception
               (RuntimeException/new "controlled exception"))]
         (is (=
               (helpers/remove-stacktrace e)
               {:type "runtime-exception",
                :class "java.lang.RuntimeException",
                :message "controlled exception",
                :cause nil,
                :data nil})))

       (let [e (sut/serialize-exception
                 (ex-info "controlled exception" {}))]
         (is (=
               (helpers/remove-stacktrace e)
               {:type "exception-info",
                :class "clojure.lang.ExceptionInfo",
                :message "controlled exception",
                :cause nil,
                :data "{}"})))

       (let [e (sut/serialize-exception
                 (Exception/new "controlled exception"))]
         (is (=
               (helpers/remove-stacktrace e)
               {:type "throwable",
                :class "java.lang.Exception",
                :message "controlled exception",
                :cause nil,
                :data nil})))


       (let [e (sut/serialize-exception
                 (ex-info "LEVEL1" {:level "1"}
                   (ex-info "LEVEL2" {:level "2"}
                     (ex-info "LEVEL2" {:level "3"}))))]
         (is (=
               (helpers/remove-stacktrace e)
               {:type "exception-info",
                :class "clojure.lang.ExceptionInfo",
                :message "LEVEL1",
                :cause
                {:type "exception-info",
                 :class "clojure.lang.ExceptionInfo",
                 :message "LEVEL2",
                 :cause
                 {:type "exception-info",
                  :class "clojure.lang.ExceptionInfo",
                  :message "LEVEL2",
                  :cause nil,
                  :data "{:level \"3\"}"},
                 :data "{:level \"2\"}"},
                :data "{:level \"1\"}"})))

       (let [e (sut/serialize-exception
                 (ex-info "LEVEL1" {:level "1"}
                   (NullPointerException/new "LEVEL2")))]
         (is
           (=
             (helpers/remove-stacktrace e)
             {:type "exception-info",
              :class "clojure.lang.ExceptionInfo",
              :message "LEVEL1",
              :cause
              {:type "runtime-exception",
               :class "java.lang.NullPointerException",
               :message "LEVEL2"
               :cause nil,
               :data nil},
              :data "{:level \"1\"}"})))

       (let [e (binding [sut/*execute-config*
                         {:debug-result false
                          :error-data-string false}]
                 (try
                   (malli/assert :int "string")
                   (catch Exception e
                     (sut/serialize-exception e))))]
         (is
           (=
             (-> e
              (helpers/remove-stacktrace)
              (update :data map?))
             {:type "exception-info",
              :class "clojure.lang.ExceptionInfo",
              :message ":malli.core/coercion",
              :cause nil,
              :data true})))))

  #?(:cljs
     (testing "Serialization exception CLJS"
       ;; -----------------
       ;; Simple Exceptions
       ;; -----------------

       (let [e (sut/serialize-exception
                 (js/Error. "controlled exception"))]
         (is (=
               (helpers/remove-stacktrace e)
               {:type "js-error"
                :class "js/Error"
                :message "controlled exception"
                :cause nil
                :data nil})))

       (let [e (sut/serialize-exception
                 (ex-info "controlled exception" {}))]
         (is (=
               (helpers/remove-stacktrace e)
               {:type "exception-info",
                :class "cljs.core.ExceptionInfo",
                :message "controlled exception",
                :cause nil
                :data "{}"})))

       (let [e (sut/serialize-exception
                 (ex-info "LEVEL1" {}
                   (ex-info "LEVEL2" {}
                     (js/Error. "LEVEL3"))))]
         (is (=
               (helpers/remove-stacktrace e)
               {:type "exception-info",
                :class "cljs.core.ExceptionInfo",
                :message "LEVEL1",
                :cause {:type "exception-info",
                        :class "cljs.core.ExceptionInfo",
                        :message "LEVEL2",
                        :cause {:type "js-error"
                                :class "js/Error"
                                :message "LEVEL3"
                                :cause nil
                                :data nil}
                        :data "{}"}
                :data "{}"})))

       (let [e (binding [sut/*execute-config*
                         {:debug-result false
                          :error-data-string false}]
                 (try
                   (malli/assert :int "string")
                   (catch :default e
                     (sut/serialize-exception e))))]
         (is
           (=
             (-> e
              (helpers/remove-stacktrace)
              (update :data map?))
             {:type "exception-info"
              :class "cljs.core.ExceptionInfo"
              :message ":malli.core/coercion"
              :cause nil
              :data true}))))))

(deftest resolve-fn
  #?(:clj
     (testing "CLJ ResolvableFn"
       ;; Supported:
       (is (= true (malli/validate sut/ResolvableFn clojure.core/str)))
       (is (= true (malli/validate sut/ResolvableFn #'clojure.core/str)))
       (is (= true (malli/validate sut/ResolvableFn 'clojure.core/str)))
       (is (= true (malli/validate sut/ResolvableFn #'str)))
       (is (= true (malli/validate sut/ResolvableFn 'str)))
       (is (= true (malli/validate sut/ResolvableFn str)))
       (is (= true (malli/validate sut/ResolvableFn :value)))
       ;; Unsupported:
       (is (= false (malli/validate sut/ResolvableFn "clojure.core/str")))
       (is (= false (malli/validate sut/ResolvableFn {})))
       (is (= false (malli/validate sut/ResolvableFn [])))
       (is (= false (malli/validate sut/ResolvableFn '())))
       (is (= false (malli/validate sut/ResolvableFn 'UNKOWN)))
       (is (= false (malli/validate sut/ResolvableFn 'UNKOWN/UNKOWN)))))
  #?(:cljs
     (testing "CLJS ResolvableFn"
       ;; Supported:
       (is (= true (malli/validate sut/ResolvableFn clojure.core/str)))
       (is (= true (malli/validate sut/ResolvableFn #'clojure.core/str)))
       (is (= true (malli/validate sut/ResolvableFn #'str)))
       (is (= true (malli/validate sut/ResolvableFn str)))
       (is (= true (malli/validate sut/ResolvableFn :value)))
       ;; Unsupported:
       (is (= false (malli/validate sut/ResolvableFn 'str)))
       (is (= false (malli/validate sut/ResolvableFn 'clojure.core/str)))
       (is (= false (malli/validate sut/ResolvableFn "clojure.core/str")))
       (is (= false (malli/validate sut/ResolvableFn {})))
       (is (= false (malli/validate sut/ResolvableFn [])))
       (is (= false (malli/validate sut/ResolvableFn '())))
       (is (= false (malli/validate sut/ResolvableFn 'UNKOWN)))
       (is (= false (malli/validate sut/ResolvableFn 'UNKOWN/UNKOWN)))
       )))


;; -----
;; Stats
;; -----

(require '[clojure.string :as str])

(defn print-stats
  "Prints a formatted summary of the execution stats from a status-map."
  ([status-map]
   (print-stats status-map nil))
  ([status-map title]
   (when-let [stats (:stats status-map)]
     (let [max-key-len (apply max 0 (map (comp count name first) stats))]
       (println (str "\nExecution Stats" (when title (str "(" title ")")) ":"))
       (doseq [[index [stat-key _ formatted]] (map-indexed vector stats)]
         (let [key-str (name stat-key)
               padding (str/join "" (repeat (- max-key-len (count key-str)) " "))]
           (println (str
                      "  " (if (= "execute" key-str) "=" (str (inc index)) )
                      "  " key-str " " padding formatted))))))))

(comment
  (print-stats
    (commando.core/execute
      [commando.commands.builtin/command-fn-spec
       commando.commands.builtin/command-from-spec
       commando.commands.builtin/command-apply-spec]
      {"1" 1
       "2" {:commando/from ["1"]}
       "3" {:commando/from ["2"]}})))

;; AVG

(defn calculate-average-stats
  "Takes a collection of status-maps and calculates the average duration for each stat-key."
  [status-maps]
  {:pre [(not-empty status-maps)]}
  (let [keys-order (map first (:stats (first status-maps)))
        all-stats (mapcat :stats status-maps)
        grouped-stats (group-by first all-stats)
        averages-grouped
        (reduce (fn [acc [stat-key measurements]]
                  (let [total-duration (reduce + (map second measurements))
                        count-measurements (count measurements)
                        average-duration (long (/ total-duration count-measurements))]
                    (assoc acc stat-key [stat-key average-duration (sut/format-time average-duration)])))
          {} grouped-stats)]
    {:stats (mapv #(get averages-grouped %) keys-order)}))

(defmacro repeat-n-and-print-stats
  "Repeats the execution of `body` `n` times, collects the status-maps,"
  [n & body]
  `(let [results# (doall (for [_# (range ~n)]
                           ~@body))
         avg-stats# (calculate-average-stats results#)]
     (print "Repeating instruction " ~n " times")
     (print-stats avg-stats#)))

(comment
  (repeat-n-and-print-stats
      30
    (commando.core/execute
      [commando.commands.builtin/command-fn-spec
       commando.commands.builtin/command-from-spec
       commando.commands.builtin/command-apply-spec]
      { ;; --------------------------------------------------------------------------------
       ;; RAW DATA & CONFIGURATION
       ;; --------------------------------------------------------------------------------
       :config
       {:commission-rates {:standard 0.07 :senior 0.12}
        :bonus-threshold 50000
        :performance-bonus 2500
        :tax-rate 0.21
        :department-op-cost {:sales 15000 :marketing 10000 :engineering 25000}}

       :products
       {"prod-001" {:name "Alpha Widget" :price 250.0}
        "prod-002" {:name "Beta Gadget" :price 475.0}
        "prod-003" {:name "Gamma Gizmo" :price 1200.0}}

       :employees
       {"emp-101" {:name "John Doe" :department :sales :level :senior}
        "emp-102" {:name "Jane Smith" :department :sales :level :standard}
        "emp-103" {:name "Peter Jones" :department :marketing :level :senior}
        "emp-201" {:name "Mary Major" :department :engineering :level :standard}}

       :sales-records
       [ ;; John's Sales
        {:employee-id "emp-101" :product-id "prod-003" :units-sold 50}
        {:employee-id "emp-101" :product-id "prod-001" :units-sold 120}
        ;; Jane's Sales
        {:employee-id "emp-102" :product-id "prod-001" :units-sold 80}
        {:employee-id "emp-102" :product-id "prod-002" :units-sold 40}
        ;; Peter's Sales (Marketing can also sell)
        {:employee-id "emp-103" :product-id "prod-002" :units-sold 10}]

       ;; --------------------------------------------------------------------------------
       ;; INTERMEDIATE CALCULATIONS
       ;; --------------------------------------------------------------------------------
       :calculations
       { ;; Step 1: Calculate the total revenue for each individual sale record.
        :sales-revenues
        {:commando/fn (fn [sales products]
                        (mapv (fn [sale]
                                (let [product (get products (:product-id sale))]
                                  (assoc sale :total-revenue (* (:units-sold sale) (:price product)))))
                          sales))
         :args [{:commando/from [:sales-records]}
                {:commando/from [:products]}]}

        ;; Step 2: Group sales by employee and calculate total sales per employee.
        :employee-sales-totals
        {:commando/fn (fn [sales-revenues]
                        (reduce (fn [acc sale]
                                  (update acc
                                    (:employee-id sale)
                                    (fnil + 0)
                                    (:total-revenue sale)))
                          {}
                          sales-revenues))
         :args [{:commando/from [:calculations :sales-revenues]}]}

        ;; Step 3: Calculate commission for each employee based on their sales total and level.
        :employee-commissions
        {:commando/apply
         {:sales-totals {:commando/from [:calculations :employee-sales-totals]}
          :employees {:commando/from [:employees]}
          :rates {:commando/from [:config :commission-rates]}}
         := (fn [{:keys [sales-totals employees rates]}]
              (into {}
                (map (fn [[emp-id total-sales]]
                       (let [employee (get employees emp-id)
                             rate-key (:level employee)
                             commission-rate (get rates rate-key 0)]
                         [emp-id (* total-sales commission-rate)]))
                  sales-totals)))}

        ;; Step 4: Determine performance bonuses for employees exceeding the threshold.
        :employee-bonuses
        {:commando/apply
         {:sales-totals {:commando/from [:calculations :employee-sales-totals]}
          :threshold {:commando/from [:config :bonus-threshold]}
          :bonus-amount {:commando/from [:config :performance-bonus]}}
         := (fn [{:keys [sales-totals threshold bonus-amount]}]
              (into {}
                (map (fn [[emp-id total-sales]]
                       [emp-id (if (> total-sales threshold) bonus-amount 0)])
                  sales-totals)))}

        ;; Step 5: Calculate total compensation (sales + commission + bonus) for each employee.
        :employee-total-compensation
        {:commando/fn (fn [commissions bonuses]
                        (merge-with + commissions bonuses))
         :args [{:commando/from [:calculations :employee-commissions]}
                {:commando/from [:calculations :employee-bonuses]}]}

        ;; Step 6: Aggregate financial data by department.
        :department-financials
        {:commando/apply
         {:employees {:commando/from [:employees]}
          :sales-totals {:commando/from [:calculations :employee-sales-totals]}
          :compensations {:commando/from [:calculations :employee-total-compensation]}
          :op-costs {:commando/from [:config :department-op-cost]}}
         := (fn [{:keys [employees sales-totals compensations op-costs]}]
              (let [initial-agg {:sales {:total-revenue 0 :total-compensation 0}
                                 :marketing {:total-revenue 0 :total-compensation 0}
                                 :engineering {:total-revenue 0 :total-compensation 0}}]
                (as-> (reduce-kv (fn [agg emp-id emp-data]
                                   (let [dept (:department emp-data)
                                         revenue (get sales-totals emp-id 0)
                                         compensation (get compensations emp-id 0)]
                                     (-> agg
                                       (update-in [dept :total-revenue] + revenue)
                                       (update-in [dept :total-compensation] + compensation))))
                        initial-agg
                        employees) data
                  (merge-with
                    (fn [dept-data op-cost]
                      (let [profit (- (:total-revenue dept-data)
                                     (+ (:total-compensation dept-data) op-cost))]
                        (assoc dept-data
                          :operating-cost op-cost
                          :net-profit profit)))
                    data
                    op-costs))))}}

       ;; --------------------------------------------------------------------------------
       ;; FINAL REPORT GENERATION
       ;; --------------------------------------------------------------------------------
       :final-report
       {:commando/apply
        {:dept-financials {:commando/from [:calculations :department-financials]}
         :total-sales-per-employee {:commando/from [:calculations :employee-sales-totals]}
         :total-compensation-per-employee {:commando/from [:calculations :employee-total-compensation]}
         :tax-rate {:commando/from [:config :tax-rate]}}
        := (fn [{:keys [dept-financials total-sales-per-employee total-compensation-per-employee tax-rate]}]
             (let [company-total-revenue (reduce + (map :total-revenue (vals dept-financials)))
                   company-total-compensation (reduce + (map :total-compensation (vals dept-financials)))
                   company-total-op-cost (reduce + (map :operating-cost (vals dept-financials)))
                   company-gross-profit (- company-total-revenue
                                          (+ company-total-compensation company-total-op-cost))
                   taxes-payable (* company-gross-profit tax-rate)
                   company-net-profit (- company-gross-profit taxes-payable)]
               {:company-summary
                {:total-revenue company-total-revenue
                 :total-compensation company-total-compensation
                 :total-operating-cost company-total-op-cost
                 :gross-profit company-gross-profit
                 :taxes-payable taxes-payable
                 :net-profit-after-tax company-net-profit}
                :department-breakdown dept-financials
                :employee-performance
                {:top-earner (key (apply max-key val total-compensation-per-employee))
                 :top-seller (key (apply max-key val total-sales-per-employee))}}))}})))

;; Flame

(defn- flame-print-stats [stats indent]
  (let [max-key-len (apply max 0 (map (comp count name first) stats))]
    (doseq [[stat-key _ formatted] stats]
      (let [key-str (name stat-key)
            padding (clojure.string/join "" (repeat (- max-key-len (count key-str)) " "))]
        (println (str indent
                   "" key-str " " padding formatted))))))

(defn flame-print [data & [indent]]
  (let [indent (or indent "")]
    (doseq [[k v] data]
      (println (str indent "———" k))
      (when (:stats v)
        (flame-print-stats (:stats v) (str indent "   |")))
      ;; рекурсивно обходимо вкладені map-и, крім :stats
      (doseq [[child-k child-v] v
              :when (map? child-v)]
        (when (not= child-k :stats)
          (flame-print {child-k child-v} (str indent "   :")))))))

(defn flamegraph [data]
  (println "Execution Flamegraph")
  (flame-print data))

(defn execute-flame [registry instruction]
  (let [stats-state (atom {})
        result
        (binding [commando.impl.utils/*execute-config*
                  {; :debug-result true
                   :hook-execute-end
                   (fn [e]
                     (swap! stats-state
                       (fn [s]
                         (update-in s (:stack commando.impl.utils/*execute-internals*)
                           #(merge % {:stats (:stats e)})))))}]
          (commando.core/execute
            registry instruction))]
    (flamegraph @stats-state)
    result))

(comment
 (execute-flame
   commando.commands.query-dsl-test/registry
   {:commando/resolve :test-instruction-qe
    :x 1
    :QueryExpression
    [
     {:resolve-instruction-qe
      [{:map [{:a
               [:b]}]}
       {:resolve-instruction
        [{:a
          [:b]}]}
       {[:resolve-instruction-qe {:x 1000}]
        [{:map [{:a
                 [:b]}]}
         {:resolve-instruction
          [{:a
            [:b]}]}
         {[:resolve-instruction-qe {:x 10000000}]
          [{:map [{:a
                   [:b]}]}]}]}]}]}))
