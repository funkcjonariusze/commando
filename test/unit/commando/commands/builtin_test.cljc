(ns commando.commands.builtin-test
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [commando.commands.builtin :as command-builtin]
   [commando.core             :as commando]
   [commando.impl.utils       :as commando-utils]
   [malli.core                :as malli]
   [commando.test-helpers     :as helpers]))

;; ===========================
;; FN-SPEC
;; ===========================

(deftest command-fn-spec
  (testing "Successfull test cases"
    (is (=
          {:vec1 [1 2 3], :vec2 [3 2 1], :result-simple 10, :result-with-deps 10}
          (:instruction
            (commando/execute [command-builtin/command-fn-spec
                               command-builtin/command-from-spec]
              {:vec1 [1 2 3]
               :vec2 [3 2 1]
               :result-simple {:commando/fn (fn [& [v1 v2]] (reduce + (map * v1 v2)))
                                :args [[1 2 3]
                                       [3 2 1]]}
               :result-with-deps {:commando/fn (fn [& [v1 v2]] (reduce + (map * v1 v2)))
                                  :args [{:commando/from [:vec1]}
                                         {:commando/from [:vec2]}]}})))
      "Uncorrectly processed :commando/fn"))
  (testing "Failure test cases"
    (is
      (helpers/status-map-contains-error?
        (binding [commando-utils/*debug-mode* true]
          (commando/execute [command-builtin/command-fn-spec]
            {:commando/fn "STRING"
             :args [[1 2 3] [3 2 1]]}))
        (fn [error]
          (=
            (-> error :error :data)
            {:command-type :commando/fn,
             :reason
             {:commando/fn
              [#?(:clj "Expected a fn, var of fn, symbol resolving to a fn"
                  :cljs "Expected a fn")]},
             :path [],
             :value {:commando/fn "STRING", :args [[1 2 3] [3 2 1]]}})))
      "Waiting on error, bacause commando/fn has wrong type for :commando/fn")
    (is
      (helpers/status-map-contains-error?
        (binding [commando-utils/*debug-mode* true]
          (commando/execute [command-builtin/command-fn-spec]
            {:commando/fn (fn [& [v1 v2]] (reduce + (map * v1 v2)))
             :args "BROKEN"}))
        (fn [error]
          (=
            (-> error :error :data (dissoc :value))
            {:command-type :commando/fn,
             :reason {:args ["should be a coll"]},
             :path []})))
      "Waiting on error, bacause commando/fn has wrong type for :args key")))

;; ===========================
;; APPLY-SPEC
;; ===========================

(deftest command-apply-spec
  (testing "Successfull test cases"
    (is (=
          {:value 1, :result-simple 2, :result-with-deps 2}
          (:instruction
           (commando/execute [command-builtin/command-apply-spec
                              command-builtin/command-from-spec]
             {:value 1
              :result-simple {:commando/apply {:value 1}
                              := (fn [e] (-> e :value inc))}
              :result-with-deps {:commando/apply {:commando/from [:value]}
                                 := inc}})))
      "Uncorrectly processed :commando/apply"))
  (testing "Failure test cases"
    (is
      (helpers/status-map-contains-error?
        (binding [commando-utils/*debug-mode* true]
          (commando/execute [command-builtin/command-apply-spec]
            {:commando/apply {:value 1}
             := "STRING"}))
        (fn [error]
          (=
            (-> error :error :data)
            {:command-type :commando/apply,
             :reason
             {:= [#?(:clj "Expected a fn, var of fn, symbol resolving to a fn"
                     :cljs "Expected a fn")]},
             :path [],
             :value {:commando/apply {:value 1}, := "STRING"}})))
      "Waiting on error, bacause commando/fn has wrong type for :commando/fn")))

;; ===========================
;; FROM-SPEC
;; ===========================

(deftest command-from-spec
    ;; -------------------
  (testing "Successfull test cases"
    (is (= {:a 1, :vec 1, :vec-map 1, :result-of-another 1}
          (get-in 
            (commando/execute [command-builtin/command-fn-spec
                               command-builtin/command-from-spec]
              {"values" {:a 1
                         :vec [1]
                         :vec-map [{:a 1}]
                         :result-of-another {:commando/fn (fn [& values] (apply + values))
                                             :args [-1 2]}}
               "result" {:a {:commando/from ["values" :a]}
                         :vec {:commando/from ["values" :vec 0]}
                         :vec-map {:commando/from ["values" :vec-map 0 :a]}
                         :result-of-another {:commando/from ["values" :result-of-another]}}})
            [:instruction "result"]))
      "Uncorrect extracting :commando/from by absolute path")
    (is (= {:a {:value 1, :result 1},
            :b {:value 2, :result 2},
            :c {:value 3, :result [3]},
            :d {:result [4 4]},
            :e {:result [5 5]}}
          (:instruction
           (commando/execute [command-builtin/command-from-spec]
             {:a {:value 1
                  :result {:commando/from ["../" :value]}}
              :b {:value 2
                  :result {:commando/from ["../" :value]}}
              :c {:value 3
                  :result [{:commando/from ["../" "../" :value]}]}
              :d {:result [4
                           {:commando/from ["../" 0]}]}
              :e {:result [5
                           {:commando/from ["./" "../" 0]}]}})))
      "Uncorrect extracting :commando/from by relative path")
    #?(:clj
       (is (= {:=-keyword 1, :=-fn 2, :=-symbol 2, :=-var 2}
             (get-in 
               (commando/execute [command-builtin/command-fn-spec
                                  command-builtin/command-from-spec]
                 {"value" {:kwd 1}
                  "result" {:=-keyword {:commando/from ["value" ] := :kwd}
                            :=-fn {:commando/from ["value"] := (fn [{:keys [kwd]}] (inc kwd))}
                            :=-symbol {:commando/from ["value" :kwd] := 'inc}
                            :=-var {:commando/from ["value" :kwd] := #'inc}}})
               [:instruction "result"])
             )
         "Uncorrect commando/from ':=' applicator. CLJ Supports: fn/keyword/var/symbol")
       :cljs (is (= {:=-keyword 1, :=-fn 2}
                   (get-in 
                     (commando/execute [command-builtin/command-fn-spec
                                        command-builtin/command-from-spec]
                       {"value" {:kwd 1}
                        "result" {:=-keyword {:commando/from ["value" ] := :kwd}
                                  :=-fn {:commando/from ["value"] := (fn [{:keys [kwd]}] (inc kwd))}}})
                     [:instruction "result"])
                   )
               "Uncorrect commando/from ':=' applicator. CLJS Supports: fn/keyword")))
  ;; -------------------
  (testing "Failure test cases"
    (is
      (helpers/status-map-contains-error?
        (commando/execute [command-builtin/command-from-spec]
          {"source" {:a 1 :b 2}
           "missing" {:commando/from ["UNEXISING"]}})
        {:message "Commando. Point dependency failed: key ':commando/from' references non-existent path [\"UNEXISING\"]",
         :path ["missing"],
         :command {:commando/from ["UNEXISING"]}})
      "Waiting on error, bacause commando/from seding to unexising path")
    (is (helpers/status-map-contains-error?
          (binding [commando-utils/*debug-mode* true]
            (commando/execute
              [command-builtin/command-from-spec]
              {:commando/from "BROKEN"}))
          (fn [error]
            (=
              (-> error :error :data)
              {:command-type :commando/from,
               :path [],
               :value {:commando/from "BROKEN"}
               :reason {:commando/from ["commando/from should be a sequence path to value in Instruction: [:some 2 \"value\"]"]}})))
      "Waiting on error, ':validate-params-fn' for commando/from. Corrupted path \"BROKEN\" ")
    (is (helpers/status-map-contains-error?
          (binding [commando-utils/*debug-mode* true]
            (commando/execute
              [command-builtin/command-from-spec]
              {:v 1
               :a {:commando/from [:v] := ["BROKEN"]}}))
          (fn [error]
            (=
              (-> error :error :data)
              {:command-type :commando/from,
               :reason {:= [#?(:clj "Expected a fn, var of fn, symbol resolving to a fn"
                               :cljs "Expected a fn")
                            "should be a string"]},
               :path [:a], :value {:commando/from [:v], := ["BROKEN"]}})))
      "Waiting on error, ':validate-params-fn' for commando/from. Wrong type for optional ':=' applicator")))


;; ===========================
;; MUTATION-SPEC
;; ===========================

(defmethod command-builtin/command-mutation :dot-product [_macro-type {:keys [vector1 vector2]}]
  (malli/assert [:+ number?] vector1)
  (malli/assert [:+ number?] vector2)
  (reduce + (map * vector1 vector2)))

(deftest command-mutation-spec
  (testing "Successfull test cases"
    (is (=
          {:vector1 [1 2 3], :vector2 [3 2 1], :result-simple 10, :result-with-deps 10}
          (:instruction
           (commando/execute [command-builtin/command-mutation-spec
                              command-builtin/command-from-spec]
             {:vector1 [1 2 3]
              :vector2 [3 2 1]
              :result-simple {:commando/mutation :dot-product
                              :vector1 [1 2 3]
                              :vector2 [3 2 1]}
              :result-with-deps {:commando/mutation :dot-product
                                 :vector1 {:commando/from [:vector1]}
                                 :vector2 {:commando/from [:vector2]}}})))
      "Uncorrectly processed :commando/mutation in dot-product example"))
  (testing "Failure test cases"
    (is
      (helpers/status-map-contains-error?
        (binding [commando-utils/*debug-mode* true]
          (commando/execute [command-builtin/command-mutation-spec]
            {:commando/mutation (fn [] "BROKEN")}))
        (fn [error]
          (=
            (-> error :error :data (dissoc :value))
            {:command-type :commando/mutation
             :reason {:commando/mutation ["should be a keyword"]}
             :path []})))
      "Waiting on error, bacause commando/mutation has wrong type for :commando/mutation")
    (is
      (helpers/status-map-contains-error?
        (binding [commando-utils/*debug-mode* true]
          (commando/execute [command-builtin/command-mutation-spec]
            {:commando/mutation :dot-product
             :vector1 [1 "_" 3]
             :vector2 [3 2 1]}))
        (fn [error]
          (=
            (-> error :error (helpers/remove-stacktrace) (dissoc :data))
            #?(:cljs
               {:type "exception-info"
                :class "cljs.core.ExceptionInfo"
                :message ":malli.core/coercion"
                :cause nil}
               :clj
               {:type "exception-info",
                :class "clojure.lang.ExceptionInfo"
                :message ":malli.core/coercion"
                :cause nil}))))
      "Waiting on error, error(malli/assert) raised inside the :dot-product mutation")))

;; ===========================
;; MACRO-SPEC
;; ===========================

(defmethod command-builtin/command-macro :string-vectors-dot-product [_macro-type {:keys [vector1-str vector2-str]}]
  {:= :dot-product
   :commando/apply
   {:vector1-str vector1-str
    :vector2-str vector2-str
    ;; -------
    ;; Parsing
    :vector1
    {:commando/fn (fn [str-vec]
                    #?(:clj (mapv #(Integer/parseInt %) str-vec)
                       :cljs (mapv #(js/parseInt %) str-vec)))
     :args [{:commando/from [:commando/apply :vector1-str]}]}
    :vector2
    {:commando/fn (fn [str-vec]
                    #?(:clj (mapv #(Integer/parseInt %) str-vec)
                       :cljs (mapv #(js/parseInt %) str-vec)))
     :args [{:commando/from [:commando/apply :vector2-str]}]}
    ;; -----------
    ;; Dot Product
    :dot-product
    {:commando/fn (fn [& [v1 v2]] (reduce + (map * v1 v2)))
     :args [{:commando/from [:commando/apply :vector1]}
            {:commando/from [:commando/apply :vector2]}]}}})

(deftest command-macro-spec
  (testing "Successfull test cases"
    (is
      (=
        {:vector-dot-1 32, :vector-dot-2 320}
        (:instruction
         (commando/execute
           [command-builtin/command-macro-spec
            command-builtin/command-fn-spec
            command-builtin/command-from-spec
            command-builtin/command-apply-spec]
           {:vector-dot-1
            {:commando/macro :string-vectors-dot-product
             :vector1-str ["1" "2" "3"]
             :vector2-str ["4" "5" "6"]}
            :vector-dot-2
            {:commando/macro :string-vectors-dot-product
             :vector1-str ["10" "20" "30"]
             :vector2-str ["4" "5" "6"]}})))))
  (testing "Failure test cases"
    (is
      (helpers/status-map-contains-error?
        (binding [commando-utils/*debug-mode* true]
          (commando/execute [command-builtin/command-macro-spec]
            {:commando/macro (fn [])}))
        (fn [error]
          (=
            (-> error :error :data (dissoc :value))
            {:command-type :commando/macro,
             :reason {:commando/macro ["should be a keyword"]},
             :path []})))
      "Waiting on error, bacause commando/mutation has wrong type for :commando/mutation")))

