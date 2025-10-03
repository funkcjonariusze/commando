(ns commando.impl.utils-test
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [clojure.set               :as set]
   [commando.impl.utils :as sut]
   [malli.core :as malli]))


(deftest throw->map
  (testing "Serialization exception"
    (is
     (=
       (->
         (sut/serialize-exception (ex-info "Exception Text" {:value1 "a" :value2 "b"}))
         (dissoc :via :trace))
       {:cause "Exception Text", :data {:value1 "a", :value2 "b"}}))))

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
