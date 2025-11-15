(ns commando.core-perf-test
  (:require
   [commando.impl.utils]
   [commando.commands.builtin]
   [commando.commands.query-dsl]
   [commando.core]
   [clojure.string :as str]
   [cljfreechart.core :as cljfreechart]))

;; =====================================
;; PRINT UTILS
;; =====================================

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

;; =======================================
;; AVERAGE EXECUTION OF REAL WORLD EXAMPLE
;; =======================================

(defn ^:private calculate-average-stats
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
                    (assoc acc stat-key [stat-key average-duration (commando.impl.utils/format-time average-duration)])))
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

(defn real-word-calculation-average-of-50 []
  (println "\n=====================Benchmark=====================")
  (println "Real Word calculation. Show average of 50 execution")
  (println "===================================================")
  (repeat-n-and-print-stats 50
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

       :calculations
       {:sales-revenues
        {:commando/fn (fn [sales products]
                        (mapv (fn [sale]
                                (let [product (get products (:product-id sale))]
                                  (assoc sale :total-revenue (* (:units-sold sale) (:price product)))))
                          sales))
         :args [{:commando/from [:sales-records]}
                {:commando/from [:products]}]}

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

        :employee-total-compensation
        {:commando/fn (fn [commissions bonuses]
                        (merge-with + commissions bonuses))
         :args [{:commando/from [:calculations :employee-commissions]}
                {:commando/from [:calculations :employee-bonuses]}]}

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


;; ==============================
;; FLAME FOR RECURSIVE INVOCATION
;; ==============================

(defn ^:private flame-print-stats [stats indent]
  (let [max-key-len (apply max 0 (map (comp count name first) stats))]
    (doseq [[stat-key _ formatted] stats]
      (let [key-str (name stat-key)
            padding (clojure.string/join "" (repeat (- max-key-len (count key-str)) " "))]
        (println (str indent
                   "" key-str " " padding formatted))))))

(defn ^:private flame-print [data & [indent]]
  (let [indent (or indent "")]
    (doseq [[k v] data]
      (println (str indent "———" k))
      (when (:stats v)
        (flame-print-stats (:stats v) (str indent "   |")))
      (doseq [[child-k child-v] v
              :when (map? child-v)]
        (when (not= child-k :stats)
          (flame-print {child-k child-v} (str indent "   :")))))))

(defn ^:private flamegraph [data]
  (println "Printing Flamegraph for executes:")
  (flame-print data))

(defn ^:private execute-with-flame [registry instruction]
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

(defmethod commando.commands.query-dsl/command-resolve :query-B [_ {:keys [x QueryExpression]}]
  (let [x (or x 10)]
    (-> {:map {:a
               {:b {:c x}
                :d {:c (inc x)
                    :f (inc (inc x))}}}
         :query-A (commando.commands.query-dsl/resolve-instruction-qe
                    "error"
                    {:commando/resolve :query-A
                     :x 1})}
      (commando.commands.query-dsl/->query-run QueryExpression))))

(defmethod commando.commands.query-dsl/command-resolve :query-A [_ {:keys [x QueryExpression]}]
  (let [x (or x 10)]
    (-> {:map {:a
               {:b {:c x}
                :d {:c (inc x)
                    :f (inc (inc x))}}}
         
         :resolve-fn       (commando.commands.query-dsl/resolve-fn
                             "error"
                             (fn [{:keys [x]}]
                               (let [y (or x 1)
                                     range-y (if (< 10 y) 10 y)]
                                 (for [z (range 0 range-y)]
                                   {:a
                                    {:b {:c (+ y z)}
                                     :d {:c (inc (+ y z))
                                         :f (inc (inc (+ y z)))}}}))))



         :instruction-A (commando.commands.query-dsl/resolve-instruction
                          "error"
                          {:commando/fn (fn [& [y]]
                                          {:a
                                           {:b {:c y}
                                            :d {:c (inc y)
                                                :f (inc (inc y))}}})
                           :args [x]})


         :query-A (commando.commands.query-dsl/resolve-instruction-qe
                    "error"
                    {:commando/resolve :query-A
                     :x 1})
         :query-B (commando.commands.query-dsl/resolve-instruction-qe
                    "error"
                    {:commando/resolve :query-B
                     :x 1})}
      (commando.commands.query-dsl/->query-run QueryExpression))))

(defn run-execute-in-depth-with-using-queryDSL []
  (println "\n===================Benchmark=====================")
  (println "Run commando/execute in depth with using queryDSL")
  (println "=================================================")
  (execute-with-flame
    [commando.commands.query-dsl/command-resolve-spec
     commando.commands.builtin/command-from-spec
     commando.commands.builtin/command-fn-spec]
    {:commando/resolve :query-A
     :x 1
     :QueryExpression
     [{:map
       [{:a
         [:b]}]}
      {:instruction-A [:a]}
      {:query-A
       [{:map
         [{:a
           [:b]}]}
        {:query-A
         [{:map
           [{:a
             [:b]}]}
          {:query-A
           [{:map
             [{:a
               [:b]}]}]}]}]}
      {:query-B
       [{:map
         [{:a
           [:b]}]}
        {:query-A
         [{:map
           [{:a
             [:b]}]}
          {:query-A
           [{:instruction-A [:a]}]}]}]}]})
)

;; =====================================
;; BUILDING DEPENDECY COMPLEX TEST CASES
;; =====================================

(defn instruction-build-v+m [{:keys [wide-n long-n]}]
  {:dependecy-token (* 2 wide-n long-n)
   :source-maps
   (mapv (fn [_n]
           (into {} (mapv (fn [v] [(keyword (str "k" v)) v])
                      (range 1 wide-n))))
     (range 1 long-n))
   :result-maps
   (mapv (fn [n]
           (into {}
             (mapv
               (fn [v]
                 (let [k (keyword (str "k" v))]
                   [k {:commando/from [:source-maps n k]}]))
               (range 1 wide-n))))
     (range 1 long-n))})

(defn instruction-build-m [{:keys [wide-n long-n]}]
  {:dependecy-token (* 2 wide-n long-n)
   :source-maps
   (reduce (fn [acc n]
             (assoc acc (keyword (str "r" n))
               (into {} (mapv (fn [v] [(keyword (str "k" v)) v])
                          (range 1 wide-n)))))
     {}
     (range 1 long-n))
   :result-maps
   (reduce (fn [acc n]
             (assoc acc (keyword (str "r" n))
               (into {}
                 (mapv
                   (fn [v]
                     (let [k (keyword (str "k" v))]
                       [k {:commando/from [:source-maps (keyword (str "r" n)) k]}]))
                   (range 1 wide-n)))))
     {}
     (range 1 long-n))})

(defn execute-complexity [{:keys [mode wide-n long-n]}]
  (let [instruction-builder (case mode
                              :m (instruction-build-m {:wide-n wide-n :long-n long-n})
                              :v+m (instruction-build-v+m {:wide-n wide-n :long-n long-n}))]
    (binding [commando.impl.utils/*execute-config*
              {:debug-result true}]
      (let [result (commando.core/execute
                     [commando.commands.builtin/command-from-spec]
                     instruction-builder)
            stats-grouped (reduce (fn [acc [k v label]]
                                    (assoc acc k v))
                            {}
                            (:stats result))]
        {:dependecy-token (:dependecy-token instruction-builder)
         :stats (:stats result)
         :stats-grouped stats-grouped}))))

;; ================================
;; PLOT LOAD TEST CASES IN PNG FILE
;; WITH USING JFREECHART
;; ================================

(defn ^:private chat-custom-styles [chart]
  (let [plotObject (.getPlot chart)
        plotObjectRenderer (.getRenderer plotObject)]
    (.setBackgroundPaint chart (java.awt.Color/new 255, 255, 255))
    (.setBackgroundPaint plotObject (java.awt.Color/new 255, 255, 255))
    (.setSeriesPaint plotObjectRenderer 0 (java.awt.Color/new 64, 115, 62))
    (.setSeriesPaint plotObjectRenderer 1 (java.awt.Color/new 62, 65, 115))
    (.setSeriesPaint plotObjectRenderer 2 (java.awt.Color/new 115, 94, 62))
    (.setSeriesPaint plotObjectRenderer 3 (java.awt.Color/new 115, 62, 62))
    (.setOutlineVisible plotObject false)
    chart))

(defn execute-steps-grow_s_x_dep []
  (println "\n==================Benchmark====================")
  (println "execute-steps(massive dep grow) secs_x_deps.png")
  (println "===============================================")
  (let [instruction-stats-result [(execute-complexity {:mode :v+m :wide-n 50 :long-n 50})
                                  (execute-complexity {:mode :v+m :wide-n 50 :long-n 500})
                                  (execute-complexity {:mode :v+m :wide-n 50 :long-n 5000})
                                  (execute-complexity {:mode :v+m :wide-n 50 :long-n 50000})]
        chart-data (mapv (fn [e] (let [{:keys [dependecy-token stats-grouped]} e]
                            (-> stats-grouped
                              (dissoc "execute")
                              (update-vals (fn [nanosecs-t]
                                             ;; (/ nanosecs-t 1000000) ;; miliseconds
                                             (/ nanosecs-t 1000000000) ;; seconds
                                             ))
                              (assoc "dependecy-token" dependecy-token))))
                     instruction-stats-result)]
    (doseq [{:keys [dependecy-token stats]} instruction-stats-result]
      (print-stats {:stats stats} (str "Dependency Counts: " dependecy-token)))
    (cljfreechart/save-chart-as-file
      (-> chart-data
        (cljfreechart/make-category-dataset {:group-key "dependecy-token"})
        (cljfreechart/make-bar-chart "commando.core/execute steps on massive count of dependencies"
          {:category-title "Dependency Counts"
           :value-title "Seconds"})
        (chat-custom-styles))
      "./test/perf/commando/execute-steps(massive dep grow) secs_x_deps.png" {:width 1200 :height 400})))

(defn execute-steps-normal_ms_x_dep []
  (println "\n================Benchmark================")
  (println "execute-steps(normal) milisecs_x_deps.png")
  (println "=========================================")
  (let [instruction-stats-result
        [(execute-complexity {:mode :m :wide-n 5 :long-n 10})
         (execute-complexity {:mode :m :wide-n 5 :long-n 14})
         (execute-complexity {:mode :m :wide-n 5 :long-n 15})
         (execute-complexity {:mode :m :wide-n 5 :long-n 20})]
        chart-data (mapv (fn [e] (let [{:keys [dependecy-token stats-grouped]} e]
                                  (-> stats-grouped
                                    (dissoc "execute")
                                    (update-vals (fn [nanosecs-t]
                                                   (/ nanosecs-t 1000000) ;; miliseconds
                                                   ;; (/ nanosecs-t 1000000000) ;; seconds
                                                   ))
                                    (assoc "dependecy-token" dependecy-token))))
                     instruction-stats-result)]
    (doseq [{:keys [dependecy-token stats]} instruction-stats-result]
      (print-stats {:stats stats} (str "Dependency Counts: " dependecy-token)))
    (cljfreechart/save-chart-as-file
      (-> chart-data
        (cljfreechart/make-category-dataset {:group-key "dependecy-token"})
        (cljfreechart/make-bar-chart "commando.core/execute steps"
          {:category-title "Dependency Counts"
           :value-title "Miliseconds"})
        (chat-custom-styles))
      "./test/perf/commando/execute-steps(normal) milisecs_x_deps.png" {:width 1200 :height 400})))

(defn execute-normal_ms_x_dep []
  (println "\n=============Benchmark=============")
  (println "execute(normal) milisecs_x_deps.png")
  (println "===================================")
  (let [instruction-stats-result
        [(execute-complexity {:mode :v+m :wide-n 25 :long-n 25})
         (execute-complexity {:mode :v+m :wide-n 50 :long-n 50})
         (execute-complexity {:mode :v+m :wide-n 100 :long-n 100})
         (execute-complexity {:mode :v+m :wide-n 200 :long-n 200})]
        chart-data (mapv (fn [e] (let [{:keys [dependecy-token stats-grouped]} e]
                            (-> stats-grouped
                              (select-keys ["execute"])
                              (update-vals (fn [nanosecs-t]
                                             (float (/ nanosecs-t 1000000)) ;; miliseconds
                                             ;; (float (/ nanosecs-t 1000000000)) ;; seconds
                                             ))
                              (assoc "dependecy-token" dependecy-token))))
               instruction-stats-result)]
    (doseq [{:keys [dependecy-token stats]} instruction-stats-result]
      (print-stats {:stats stats} (str "Dependency Counts: " dependecy-token)))
    (cljfreechart/save-chart-as-file
      (-> chart-data
        (cljfreechart/make-category-dataset {:group-key "dependecy-token"})
        (cljfreechart/make-bar-chart "commando.core/execute times"
          {:category-title "Dependency Counts"
           :value-title "Miliseconds"})
        (chat-custom-styles))
      "./test/perf/commando/execute(normal) milisecs_x_deps.png" {:width 1200 :height 400})))

(defn -main []
  ;; Execution stats.
  (real-word-calculation-average-of-50)
  (run-execute-in-depth-with-using-queryDSL)
  ;; Drow plot for special cases.
  (execute-steps-normal_ms_x_dep)
  (execute-normal_ms_x_dep)
  (execute-steps-grow_s_x_dep))

