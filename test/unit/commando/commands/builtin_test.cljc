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
            (commando/execute {:commando/fn command-builtin/command-fn-spec
                               :commando/from command-builtin/command-from-spec}
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
        (binding [commando-utils/*execute-config*
                  {:debug-result false
                   :error-data-string false}]
          (commando/execute {:commando/fn command-builtin/command-fn-spec}
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
        (binding [commando-utils/*execute-config*
                  {:debug-result false
                   :error-data-string false}]
          (commando/execute {:commando/fn command-builtin/command-fn-spec}
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
           (commando/execute {:commando/apply command-builtin/command-apply-spec
                              :commando/from command-builtin/command-from-spec}
             {:value 1
              :result-simple {:commando/apply {:value 1}
                              :=> [:fn (fn [e] (-> e :value inc))]}
              :result-with-deps {:commando/apply {:commando/from [:value]}
                                 :=> [:fn inc]}})))
      "Uncorrectly processed :commando/apply")))

;; ===========================
;; FROM-SPEC
;; ===========================

(deftest command-from-spec
  ;; -------------------
  (testing "Successfull test cases"
    (is (= {:a 1, :vec 1, :vec-map 1, :result-of-another 1}
          (get-in
            (commando/execute {:commando/fn command-builtin/command-fn-spec
                               :commando/from command-builtin/command-from-spec}
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
           (commando/execute {:commando/from command-builtin/command-from-spec}
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
    (is (= {"a" {"value" 1, "result" 1},
            "b" {"value" 2, "result" 2},
            "c" {"value" 3, "result" [3]},
            "d" {"result" [4 4]},
            "e" {"result" [5 5]}}
          (:instruction
           (commando/execute {:commando/from command-builtin/command-from-spec}
             {"a" {"value" 1
                   "result" {"commando-from" ["../" "value"]}}
              "b" {"value" 2
                   "result" {"commando-from" ["../" "value"]}}
              "c" {"value" 3
                   "result" [{"commando-from" ["../" "../" "value"]}]}
              "d" {"result" [4
                             {"commando-from" ["../" 0]}]}
              "e" {"result" [5
                             {"commando-from" ["./" "../" 0]}]}})))
      "Uncorrect extracting \"commando-from\" by relative path")
    #?(:clj
       (is (= {:get-kwd 1, :fn-fn 2, :fn-symbol 2, :fn-var 2}
             (get-in
               (commando/execute {:commando/fn command-builtin/command-fn-spec
                                  :commando/from command-builtin/command-from-spec}
                 {"value" {:kwd 1}
                  "result" {:get-kwd {:commando/from ["value"] :=> [:get :kwd]}
                            :fn-fn {:commando/from ["value"] :=> [:fn (fn [{:keys [kwd]}] (inc kwd))]}
                            :fn-symbol {:commando/from ["value" :kwd] :=> [:fn 'inc]}
                            :fn-var {:commando/from ["value" :kwd] :=> [:fn #'inc]}}})
               [:instruction "result"]))
         "commando/from :=> drivers. CLJ: :get/:fn with fn/keyword/var/symbol")
       :cljs (is (= {:get-kwd 1, :fn-fn 2}
                   (get-in
                     (commando/execute {:commando/fn command-builtin/command-fn-spec
                                        :commando/from command-builtin/command-from-spec}
                       {"value" {:kwd 1}
                        "result" {:get-kwd {:commando/from ["value"] :=> [:get :kwd]}
                                  :fn-fn {:commando/from ["value"] :=> [:fn (fn [{:keys [kwd]}] (inc kwd))]}}})
                     [:instruction "result"]))
               "commando/from :=> drivers. CLJS: :get/:fn with fn/keyword")))
  ;; -------------------
  (testing "Anchor navigation"
    (is (= {:section {:__anchor "root" :price 10 :ref 10}}
           (:instruction
            (commando/execute {:commando/from command-builtin/command-from-spec}
              {:section {:__anchor "root"
                         :price 10
                         :ref {:commando/from ["@root" :price]}}})))
        "Basic anchor: command resolves to nearest ancestor with __anchor = 'root'")
    (is (= {:items [{:__anchor "item" :price 10 :ref 10}
                    {:__anchor "item" :price 20 :ref 20}]}
           (:instruction
            (commando/execute {:commando/from command-builtin/command-from-spec}
              {:items [{:__anchor "item"
                        :price 10
                        :ref {:commando/from ["@item" :price]}}
                       {:__anchor "item"
                        :price 20
                        :ref {:commando/from ["@item" :price]}}]})))
        "Duplicate anchors: each command finds its own nearest ancestor")
    (is (= {:catalog {:__anchor "root"
                      :base-price 5
                      :section {:__anchor "section" :price 10 :sibling-price 5}}}
           (:instruction
            (commando/execute {:commando/from command-builtin/command-from-spec}
              {:catalog {:__anchor "root"
                         :base-price 5
                         :section {:__anchor "section"
                                   :price 10
                                   :sibling-price {:commando/from ["@section" "../" :base-price]}}}})))
        "Anchor combined with ../: jump to anchor then go up one level")
    (is (= {:root-1
            {:__anchor "root-1"
             :price 5
             :root-2
             {:__anchor "root-2"
              :price 10
              :root-3
              {:price-1 5
               :price-2 10}}}}
          (:instruction
           (commando/execute {:commando/from command-builtin/command-from-spec}
             {:root-1
              {:__anchor "root-1"
               :price 5
               :root-2
               {:__anchor "root-2"
                :price 10
                :root-3
                {:price-1 {:commando/from ["@root-1" :price]}
                 :price-2 {:commando/from ["@root-2" :price]}}}}})))
      "Different level anchor combined in one level"))
  ;; -------------------
  (testing "Failure test cases"
    (is
      (helpers/status-map-contains-error?
        (commando/execute {:commando/from command-builtin/command-from-spec}
          {:ref {:commando/from ["@nonexistent" :value]}})
        {:message "Commando. Point dependency failed: key ':commando/from' references non-existent path [\"@nonexistent\" :value]",
         :path [:ref],
         :command {:commando/from ["@nonexistent" :value]}})
      "Anchor not found: should produce error with :anchor key in data")
    (is
      (helpers/status-map-contains-error?
        (commando/execute {:commando/from command-builtin/command-from-spec}
          {"source" {:a 1 :b 2}
           "missing" {:commando/from ["UNEXISING"]}})
        {:message "Commando. Point dependency failed: key ':commando/from' references non-existent path [\"UNEXISING\"]",
         :path ["missing"],
         :command {:commando/from ["UNEXISING"]}})
      "Waiting on error, bacause commando/from seding to unexising path")
    (is
      (helpers/status-map-contains-error?
        (commando/execute {:commando/from command-builtin/command-from-spec}
          {"source" {:a 1 :b 2}
           "missing" {"commando-from" ["UNEXISING"]}})
        {:message "Commando. Point dependency failed: key 'commando-from' references non-existent path [\"UNEXISING\"]",
         :path ["missing"],
         :command {"commando-from" ["UNEXISING"]}})
      "Waiting on error, bacause \"commando-from\" seding to unexising path")
    (is
      (helpers/status-map-contains-error?
        (binding [commando-utils/*execute-config*
                  {:debug-result false
                   :error-data-string false}]
          (commando/execute {:commando/from command-builtin/command-from-spec}
            {"value" 1
             "result" {:commando/from ["value"]
                       "commando-from" ["value"]}}))
        (fn [error]
          (=
            (-> error :error :data)
            {:command-type :commando/from,
             :reason "The keyword :commando/from and the string \"commando-from\" cannot be used simultaneously in one command.",
             :path ["result"],
             :value {:commando/from ["value"], "commando-from" ["value"]}})))
      "Using string and keyword form shouldn't be allowed")
    (is (helpers/status-map-contains-error?
          (binding [commando-utils/*execute-config*
                    {:debug-result false
                     :error-data-string false}]
            (commando/execute
              {:commando/from command-builtin/command-from-spec}
              {:commando/from "BROKEN"}))
          (fn [error]
            (=
              (-> error :error :data)
              {:command-type :commando/from,
               :path [],
               :value {:commando/from "BROKEN"}
               :reason {:commando/from ["commando/from should be a sequence path to value in Instruction: [:some 2 \"value\"]"]}})))
      "Waiting on error, ':validate-params-fn' for commando/from. Corrupted path \"BROKEN\" ")
))

;; ===========================
;; CONTEXT-SPEC
;; ===========================

(def test-ctx
  {:colors  {:red "#FF0000" :blue "#0000FF"}
   :numbers [10 20 30]
   :nested  {:a {:b {:c 42}}}})

(deftest command-context-spec-success
  (let [ctx-spec (command-builtin/command-context-spec test-ctx)]
    (testing "Successfull test cases"
      (is (= {:color "#FF0000" :number 10 :deep 42}
            (:instruction
             (commando/execute {:commando/context ctx-spec}
               {:color  {:commando/context [:colors :red]}
                :number {:commando/context [:numbers 0]}
                :deep   {:commando/context [:nested :a :b :c]}})))
        "Should resolve keyword path in context with varios deepnest")

      (is (= {:val "#0000FF" :count 2}
            (:instruction
             (commando/execute {:commando/context ctx-spec}
               {:count {:commando/context [:colors] :=> [:fn count]}
                :val {:commando/context [:colors] :=> [:get :blue]}})))
        "Should apply :=> driver to resolved value")

      (is (= {:val-default "fallback" :val-nil nil}
            (:instruction
             (commando/execute {:commando/context ctx-spec}
               {:val-default {:commando/context [:nonexistent] :default "fallback"}
                :val-nil     {:commando/context [:nonexistent] :default nil}})))
        "Should return :default value when path not found, in other way 'nil' value without exception ")

      (let [str-ctx {"lang" {"ua" "Ukrainian" "en" "English"}}
            str-spec (command-builtin/command-context-spec str-ctx)]
        (is (= {"val" "Ukrainian" "val-default" "none" "val-get" "English"}
              (:instruction
               (commando/execute {:commando/context str-spec}
                 {"val"          {"commando-context" ["lang" "ua"]}
                  "val-get"      {"commando-context" ["lang"] "=>" ["get" "en"]}
                  "val-default"  {"commando-context" ["missing"] "default" "none"}})))
          "String keys test")))
    (testing "Failure test cases"
      (is (helpers/status-map-contains-error?
            (binding [commando-utils/*execute-config*
                      {:debug-result false :error-data-string false}]
              (commando/execute {:commando/context ctx-spec}
                {:val {:commando/context "NOT-A-PATH"}}))
            (fn [error]
              (= (-> error :error :data)
                {:command-type :commando/context
                 :reason {:commando/context ["commando/context should be a sequential path: [:some :key]"]}
                 :path [:val]
                 :value {:commando/context "NOT-A-PATH"}})))
        "Should fail validation when path is not sequential")
      (is (helpers/status-map-contains-error?
            (binding [commando-utils/*execute-config*
                      {:debug-result false :error-data-string false}]
              (commando/execute {:commando/context ctx-spec}
                {:val {:commando/context [:colors] "commando-context" ["colors"]}}))
            (fn [error]
              (= (-> error :error :data)
                {:command-type :commando/context
                 :reason "The keyword :commando/context and the string \"commando-context\" cannot be used simultaneously in one command."
                 :path [:val]
                 :value {:commando/context [:colors] "commando-context" ["colors"]}})))
        "Should not allow both keyword and string form"))))


;; ===========================
;; MUTATION-SPEC
;; ===========================

(defmethod command-builtin/command-mutation :dot-product [_macro-type {:keys [vector1 vector2]}]
  (malli/assert [:+ number?] vector1)
  (malli/assert [:+ number?] vector2)
  (reduce + (map * vector1 vector2)))

(defmethod command-builtin/command-mutation "dot-product" [_macro-type {:strs [vector1 vector2]}]
  (malli/assert [:+ number?] vector1)
  (malli/assert [:+ number?] vector2)
  (reduce + (map * vector1 vector2)))

(deftest command-mutation-spec
  (testing "Successfull test cases"
    (is (=
          {:vector1 [1 2 3], :vector2 [3 2 1], :result-simple 10, :result-with-deps 10}
          (:instruction
           (commando/execute {:commando/mutation command-builtin/command-mutation-spec
                              :commando/from command-builtin/command-from-spec}
             {:vector1 [1 2 3]
              :vector2 [3 2 1]
              :result-simple {:commando/mutation :dot-product
                              :vector1 [1 2 3]
                              :vector2 [3 2 1]}
              :result-with-deps {:commando/mutation :dot-product
                                 :vector1 {:commando/from [:vector1]}
                                 :vector2 {:commando/from [:vector2]}}})))
      "Uncorrectly processed :commando/mutation in dot-product example")
    (is (=
          {"vector1" [1 2 3], "vector2" [3 2 1], "result-simple" 10, "result-with-deps" 10}
          (:instruction
           (commando/execute {:commando/mutation command-builtin/command-mutation-spec
                              :commando/from command-builtin/command-from-spec}
             {"vector1" [1 2 3]
              "vector2" [3 2 1]
              "result-simple" {"commando-mutation" "dot-product"
                              "vector1" [1 2 3]
                              "vector2" [3 2 1]}
              "result-with-deps" {"commando-mutation" "dot-product"
                                 "vector1" {"commando-from" ["vector1"]}
                                 "vector2" {"commando-from" ["vector2"]}}})))
      "Uncorrectly processed \"commando/mutation\" in dot-product example"))
  (testing "Failure test cases"
    (is
      (helpers/status-map-contains-error?
        (binding [commando-utils/*execute-config*
                  {:debug-result false
                   :error-data-string false}]
          (commando/execute {:commando/mutation command-builtin/command-mutation-spec}
            {:commando/mutation :dot-product
             "commando-mutation" "dot-product"
             "vector1" [1 2 3]
             "vector2" [3 2 1]}))
        (fn [error]
          (=
            (-> error :error :data)
            {:command-type :commando/mutation,
             :reason "The keyword :commando/mutation and the string \"commando-mutation\" cannot be used simultaneously in one command.",
             :path [],
             :value
             {:commando/mutation :dot-product,
              "commando-mutation" "dot-product",
              "vector1" [1 2 3],
              "vector2" [3 2 1]}})))
      "Using string and keyword form shouldn't be allowed")
    (is
      (helpers/status-map-contains-error?
        (binding [commando-utils/*execute-config*
                  {:debug-result false
                   :error-data-string false}]
          (commando/execute {:commando/mutation command-builtin/command-mutation-spec}
            {:commando/mutation (fn [] "BROKEN")}))
        (fn [error]
          (=
            (-> error :error :data (dissoc :value))
            {:command-type :commando/mutation
             :reason {:commando/mutation ["should be a keyword" "should be a string"]}
             :path []})))
      "Waiting on error, bacause commando/mutation has wrong type for :commando/mutation")
    (is
      (helpers/status-map-contains-error?
        (binding [commando-utils/*execute-config*
                  {:debug-result false
                   :error-data-string false}]
          (commando/execute {:commando/mutation command-builtin/command-mutation-spec}
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

(defn string-vector-dot-product [vector1-str vector2-str]
  {:=> [:get :dot-product]
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

(defmethod command-builtin/command-macro :string-vectors-dot-product [_macro-type {:keys [vector1-str vector2-str]}]
  (string-vector-dot-product vector1-str vector2-str))
(defmethod command-builtin/command-macro "string-vectors-dot-product" [_macro-type {:strs [vector1-str vector2-str]}]
  (string-vector-dot-product vector1-str vector2-str))

(deftest command-macro-spec
  (testing "Successfull test cases"
    (is
      (=
        {:vector-dot-1 32, :vector-dot-2 320}
        (:instruction
         (commando/execute
           {:commando/macro command-builtin/command-macro-spec
            :commando/fn command-builtin/command-fn-spec
            :commando/from command-builtin/command-from-spec
            :commando/apply command-builtin/command-apply-spec}
           {:vector-dot-1
            {:commando/macro :string-vectors-dot-product
             :vector1-str ["1" "2" "3"]
             :vector2-str ["4" "5" "6"]}
            :vector-dot-2
            {:commando/macro :string-vectors-dot-product
             :vector1-str ["10" "20" "30"]
             :vector2-str ["4" "5" "6"]}})))
      "Uncorrectly processed :commando/macro for :string-vectors-dot-product example")
    (is
      (=
        {"vector-dot-1" 32, "vector-dot-2" 320}
        (:instruction
         (commando/execute
           {:commando/macro command-builtin/command-macro-spec
            :commando/fn command-builtin/command-fn-spec
            :commando/from command-builtin/command-from-spec
            :commando/apply command-builtin/command-apply-spec}
           {"vector-dot-1"
            {"commando-macro" "string-vectors-dot-product"
             "vector1-str" ["1" "2" "3"]
             "vector2-str" ["4" "5" "6"]}
            "vector-dot-2"
            {"commando-macro" "string-vectors-dot-product"
             "vector1-str" ["10" "20" "30"]
             "vector2-str" ["4" "5" "6"]}})))
      "Uncorrectly processed \"commando-macro\" for \"string-vectors-dot-product\" example"))
  (testing "Failure test cases"
    (is
      (helpers/status-map-contains-error?
        (binding [commando-utils/*execute-config*
                  {:debug-result false
                   :error-data-string false}]
          (commando/execute
            {:commando/macro command-builtin/command-macro-spec
             :commando/fn command-builtin/command-fn-spec
             :commando/from command-builtin/command-from-spec
             :commando/apply command-builtin/command-apply-spec}
            {:commando/macro :string-vectors-dot-product
             "commando-macro" "string-vectors-dot-product"
             "vector1-str" ["1" "2" "3"]
             "vector2-str" ["4" "5" "6"]}))
        (fn [error]
          (=
            (-> error :error :data)
            {:command-type :commando/macro,
             :reason "The keyword :commando/macro and the string \"commando-macro\" cannot be used simultaneously in one command.",
             :path [],
             :value
             {:commando/macro :string-vectors-dot-product
              "commando-macro" "string-vectors-dot-product"
              "vector1-str" ["1" "2" "3"]
              "vector2-str" ["4" "5" "6"]}})))
      "Using string and keyword form shouldn't be allowed")
    (is
      (helpers/status-map-contains-error?
        (binding [commando-utils/*execute-config*
                  {:debug-result false
                   :error-data-string false}]
          (commando/execute {:commando/macro command-builtin/command-macro-spec}
            {:commando/macro (fn [])}))
        (fn [error]
          (=
            (-> error :error :data (dissoc :value))
            {:command-type :commando/macro,
             :reason {:commando/macro ["should be a keyword" "should be a string"]},
             :path []})))
      "Waiting on error, bacause commando/mutation has wrong type for :commando/mutation")))
