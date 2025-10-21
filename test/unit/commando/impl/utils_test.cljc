(ns commando.impl.utils-test
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [clojure.set               :as set]
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
               (dissoc e :stack-trace)
               {:type "runtime-exception",
                :class "java.lang.RuntimeException",
                :message "controlled exception",
                :cause nil,
                :data nil})))

       (let [e (sut/serialize-exception
                 (ex-info "controlled exception" {}))]
         (is (=
               (dissoc e :stack-trace)
               {:type "exception-info",
                :class "clojure.lang.ExceptionInfo",
                :message "controlled exception",
                :cause nil,
                :data "{}"})))

       (let [e (sut/serialize-exception
                 (Exception/new "controlled exception"))]
         (is (=
               (dissoc e :stack-trace)
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
               (-> e
                 (dissoc :stack-trace)
                 (update-in [:cause] dissoc :stack-trace)
                 (update-in [:cause :cause] dissoc :stack-trace))
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
             (-> e
               (dissoc :stack-trace)
               (update-in [:cause] dissoc :stack-trace))
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

       (let [e (binding [sut/*debug-mode* true]
                 (try
                   (malli/assert :int "string")
                   (catch Exception e
                     (sut/serialize-exception e))))]
         (is
           (=
             (-> e
               (dissoc :stack-trace)
               (update-in [:data] map?))
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
               (-> e
                 (dissoc :stack-trace))
               {:type "js-error"
                :class "js/Error"
                :message "controlled exception"
                :cause nil
                :data nil})))

       (let [e (sut/serialize-exception
                 (ex-info "controlled exception" {}))]
         (is (=
               (-> e
                 (dissoc :stack-trace))
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
               (-> e
                 (dissoc :stack-trace)
                 (update-in [:cause] dissoc :stack-trace)
                 (update-in [:cause :cause] dissoc :stack-trace))
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

       (let [e (binding [sut/*debug-mode* true]
                 (try
                   (malli/assert :int "string")
                   (catch :default e
                     (sut/serialize-exception e))))]
         (is
           (=
             (-> e
               (dissoc :stack-trace)
               (update-in [:data] map?))
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
