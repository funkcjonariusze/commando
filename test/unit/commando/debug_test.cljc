(ns commando.debug-test
  "Visual samples for commando.debug — run to see printed output.
   Not strict unit tests; primarily for eyeballing debug output."
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [commando.commands.builtin :as builtin]
   [commando.core :as commando]
   [commando.debug :as debug]))

;; ============================================================
;; Domain mutations (same as account_test)
;; ============================================================

(defmethod builtin/command-mutation :allocate
  [_ {:keys [percent from]}]
  (* from (/ percent 100.0)))

(defmethod builtin/command-mutation :deduct
  [_ {:keys [from amount]}]
  (- from amount))

(defmethod builtin/command-mutation :half-of
  [_ {:keys [from]}]
  (/ from 2))

(defmethod builtin/command-mutation :rate
  [_ {:keys [from factor]}]
  (* from factor))

;; ============================================================
;; Shared test data
;; ============================================================

(def ^:private distribution-instruction
  {:money {:commando/context [:money]}
   :VAL1  {:commando/context [:VAL1]}
   :ACCOUNT-1
   {:commando/from [:money]}
   :ACCOUNT-1-1
   {:commando/mutation :allocate
    :percent 10
    :from {:commando/from [:ACCOUNT-1]}}
   :ACCOUNT-1-1-1
   {:commando/mutation :allocate
    :percent 50
    :from {:commando/from [:ACCOUNT-1-1]}}
   :ACCOUNT-1-1-1-1
   {:commando/mutation :allocate
    :percent 1
    :from {:commando/from [:ACCOUNT-1-1-1]}}
   :ACCOUNT-1-1-2
   {:commando/mutation :allocate
    :percent 50
    :from {:commando/from [:ACCOUNT-1-1]}}
   :ACCOUNT-1-2
   {:commando/mutation :allocate
    :percent 10
    :from {:commando/from [:ACCOUNT-1]}}
   :ACCOUNT-1-3
   {:commando/mutation :allocate
    :percent 80
    :from {:commando/from [:ACCOUNT-1]}}
   :XVAL-half
   {:commando/mutation :half-of
    :from {:commando/from [:VAL1]}}
   :XVAL
   {:commando/mutation :rate
    :from {:commando/from [:XVAL-half]}
    :factor 0.25}
   :ACCOUNT-1-3-1
   {:commando/mutation :deduct
    :from {:commando/from [:ACCOUNT-1-3]}
    :amount {:commando/from [:XVAL]}}})

(defn ^:private make-distribution-registry [input]
  [(builtin/command-context-spec input)
   builtin/command-from-spec
   builtin/command-mutation-spec])

;; ============================================================
;; execute-debug modes — accounting distribution
;; ============================================================

(deftest debug-tree-mode-test
  (let [input    {:money 1000 :VAL1 10}
        registry (make-distribution-registry input)]

    (testing ":tree mode"
      (println "\n--- :tree mode ---")
      (let [r (debug/execute-debug registry distribution-instruction :tree)]
        (is (commando/ok? r))
        (is (= 798.75 (get-in (:instruction r) [:ACCOUNT-1-3-1])))))

    (testing ":table mode"
      (println "\n--- :table mode ---")
      (let [r (debug/execute-debug registry distribution-instruction :table)]
        (is (commando/ok? r))))

    (testing ":graph mode"
      (println "\n--- :graph mode ---")
      (let [r (debug/execute-debug registry distribution-instruction :graph)]
        (is (commando/ok? r))))

    (testing ":stats mode"
      (println "\n--- :stats mode ---")
      (let [r (debug/execute-debug registry distribution-instruction :stats)]
        (is (commando/ok? r))))))

;; ============================================================
;; execute-trace — nested macro/mutation
;; ============================================================

(defmethod builtin/command-mutation :rand-n
  [_macro-type {:keys [v]}]
  (:instruction
   (commando/execute
     [builtin/command-apply-spec]
     {:commando/apply v
      := (fn [n] (rand-int n))})))

(defmethod builtin/command-macro :sum-n
  [_macro-type {:keys [v]}]
  {:__title "Summarize Random"
   :commando/fn (fn [& v-coll] (apply + v-coll))
   :args [v
          {:commando/mutation :rand-n
           :v 200}]})

(deftest trace-nested-macro-mutation-test
  (testing "execute-trace with nested macro + mutation"
    (println "\n--- execute-trace: nested macro/mutation ---")
    (let [r (debug/execute-trace
              #(commando/execute
                 [builtin/command-fn-spec
                  builtin/command-from-spec
                  builtin/command-macro-spec
                  builtin/command-mutation-spec]
                 {:value {:commando/mutation :rand-n :v 200}
                  :result {:commando/macro :sum-n
                           :v {:commando/from [:value]}}}))]
      (is (commando/ok? r)))))

;; ============================================================
;; execute-trace — vector dot product
;; ============================================================

(defmethod builtin/command-macro :v-str->v-int
  [_macro-type {:keys [vector-str]}]
  {:commando/fn (fn [str-vec]
                  (mapv #(Integer/parseInt %) str-vec))
   :args [vector-str]})

(defmethod builtin/command-macro :vector-dot-product
  [_macro-type {:keys [vector1-str vector2-str]}]
  {:=> [:get :dot-product]
   :commando/apply
   {:vector1-str vector1-str
    :vector2-str vector2-str
    :vector1
    {:commando/macro :v-str->v-int
     :vector-str {:commando/from ["../" "../" :vector1-str]}}
    :vector2
    {:commando/macro :v-str->v-int
     :vector-str {:commando/from ["../" "../" :vector2-str]}}
    :dot-product
    {:commando/fn (fn [& [v1 v2]] (reduce + (map * v1 v2)))
     :args [{:commando/from ["../" "../" "../" :vector1]}
            {:commando/from ["../" "../" "../" :vector2]}]}}})

(deftest trace-vector-dot-product-test
  (testing "execute-trace with vector dot product macro"
    (println "\n--- execute-trace: vector dot product ---")
    (let [r (debug/execute-trace
              #(commando/execute
                 [builtin/command-macro-spec
                  builtin/command-fn-spec
                  builtin/command-from-spec
                  builtin/command-apply-spec]
                 {:vector-dot-1
                  {:commando/macro :vector-dot-product
                   :vector1-str ["1" "2" "3"]
                   :vector2-str ["4" "5" "6"]}
                  :vector-dot-2
                  {:commando/macro :vector-dot-product
                   :vector1-str ["10" "20" "30"]
                   :vector2-str ["4" "5" "6"]}}))]
      (is (commando/ok? r))
      (is (= 32 (get-in (:instruction r) [:vector-dot-1])))
      (is (= 320 (get-in (:instruction r) [:vector-dot-2]))))))

;; ============================================================
;; execute-debug modes — with drivers
;; ============================================================

(deftest debug-with-drivers-test
  (let [input       {:user {:name "Alice" :age 30 :role "admin"}}
        instruction {:user-data {:commando/context [:user]}
                     :user-name {:commando/from [:user-data]
                                 :=> [:get :name]}
                     :user-role {:commando/from [:user-data]
                                 :=> [:get :role]}
                     :greeting  {:commando/fn (fn [name role]
                                                (str "Hello " name " (" role ")"))
                                 :args [{:commando/from [:user-name]}
                                        {:commando/from [:user-role]}]}}
        registry    [(builtin/command-context-spec input)
                     builtin/command-from-spec
                     builtin/command-fn-spec]]

    (testing ":tree with drivers"
      (println "\n--- :tree with drivers ---")
      (let [r (debug/execute-debug registry instruction :tree)]
        (is (commando/ok? r))
        (is (= "Hello Alice (admin)" (get-in (:instruction r) [:greeting])))))

    (testing ":table with drivers"
      (println "\n--- :table with drivers ---")
      (let [r (debug/execute-debug registry instruction :table)]
        (is (commando/ok? r))))

    (testing "combined [:instr-before :table :instr-after]"
      (println "\n--- combined mode ---")
      (let [r (debug/execute-debug registry instruction [:instr-before :table :instr-after])]
        (is (commando/ok? r))))))
