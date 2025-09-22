(ns commando.impl.command-map-test
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [clojure.set               :as set]
   [commando.impl.command-map :as sut]))

(deftest command-map
  (testing "Path objects"
    (let [path-obj (sut/->CommandMapPath ["product" 0] {:a 99})]
      (is (= (sut/command-path path-obj) ["product" 0]))
      (is (= (sut/command-data path-obj) {:a 99}))
      (is (= (sut/->CommandMapPath ["A"] {:a "ONE"}) (sut/->CommandMapPath ["A"] {:a "ONE"})))
      (is (not= (sut/->CommandMapPath ["A"] {:a "ONE"}) (sut/->CommandMapPath ["CHANGED"] {:a "ONE"})))
      (is (= (sut/->CommandMapPath ["A"] {:a "ONE"}) (sut/->CommandMapPath ["A"] {:a "CHANGED"})))
      (is (= (sut/->CommandMapPath ["A"] {:a "ONE"}) (sut/->CommandMapPath ["A"] {:a "CHANGED"}))))
    (is (= (conj #{(sut/->CommandMapPath ["product" 0] {:a 99})} (sut/->CommandMapPath ["product" 0] {:a 99}))
           #{(sut/->CommandMapPath ["product" 0] {:a "NOT SAME"})}))
    (is (= (disj #{(sut/->CommandMapPath ["product" 0] {:a 99}) (sut/->CommandMapPath ["product" 1] {:a 99})}
                 (sut/->CommandMapPath ["product" 1] {:a "ANOTHER"}))
           #{(sut/->CommandMapPath ["product" 0] {:a 99})}))
    (is (= (mapv str
                 (sort-by sut/command-path
                          [(sut/->CommandMapPath ["A" 10] {:a 99})
                           (sut/->CommandMapPath ["A" 0] {:a 99})
                           (sut/->CommandMapPath ["B"] {:a 99})
                           (sut/->CommandMapPath ["X"] {:a 99})]))
           ["root,B" "root,X" "root,A,0" "root,A,10"]))
    (is (= (mapv str
                 (sort-by str
                          [(sut/->CommandMapPath ["A" 10] {:a 99})
                           (sut/->CommandMapPath ["A" 0] {:a 99})
                           (sut/->CommandMapPath ["B"] {:a 99})
                           (sut/->CommandMapPath ["X"] {:a 99})]))
           ["root,A,0" "root,A,10" "root,B" "root,X"]))
    (let [map-with-paths
          {(sut/->CommandMapPath ["cheque"] {:a 99}) #{(sut/->CommandMapPath ["product" 0] {:a 99})
                                                       (sut/->CommandMapPath ["product" 1] {:a 99})}
           (sut/->CommandMapPath ["product" 0] {:a 2}) #{(sut/->CommandMapPath ["product" 0 :productValue] {:a 2})}
           (sut/->CommandMapPath ["product" 0 :productValue] {:a 1}) #{}
           (sut/->CommandMapPath ["product" 1] {:a 2}) #{(sut/->CommandMapPath ["product" 1 :productValue] {:a 2})}
           (sut/->CommandMapPath ["product" 1 :productValue] {:a 1}) #{}}]
      (is (= (get map-with-paths (sut/->CommandMapPath ["cheque"] {}))
             #{(sut/->CommandMapPath ["product" 0] {}) (sut/->CommandMapPath ["product" 1] {})}))))
  (testing "command-id function - returns string representation"
    (let [cmd-path (sut/->CommandMapPath ["goal" :sub] {:type :test})]
      (is (= "root,goal,:sub[test]" (sut/command-id cmd-path)) "Returns correct string ID for command")
      (is (nil? (sut/command-id {})) "Returns nil for non-CommandMapPath objects")
      (is (nil? (sut/command-id nil)) "Returns nil for nil input")
      (is (nil? (sut/command-id "string")) "Returns nil for string input")))
  (testing "command-map? predicate - identifies CommandMapPath objects"
    (let [cmd-path (sut/->CommandMapPath ["test"] {})]
      (is (sut/command-map? cmd-path) "Returns true for CommandMapPath objects")
      (is (not (sut/command-map? {})) "Returns false for regular maps")
      (is (not (sut/command-map? [])) "Returns false for vectors")
      (is (not (sut/command-map? nil)) "Returns false for nil")
      (is (not (sut/command-map? "string")) "Returns false for strings")))
  (testing "start-with? function - tests path hierarchy relationships"
    (let [parent (sut/->CommandMapPath ["goal"] {})
          child (sut/->CommandMapPath ["goal" :sub] {})
          grandchild (sut/->CommandMapPath ["goal" :sub :deep] {})
          unrelated (sut/->CommandMapPath ["other"] {})]
      (is (sut/start-with? child parent) "Child path starts with parent path")
      (is (sut/start-with? grandchild parent) "Grandchild path starts with parent path")
      (is (sut/start-with? grandchild child) "Grandchild path starts with child path")
      (is (not (sut/start-with? parent child)) "Parent path does not start with child path")
      (is (not (sut/start-with? child unrelated)) "Unrelated paths do not start with each other")
      (is (sut/start-with? parent parent) "Path starts with itself")))
  (testing "API functions with edge cases - handle unusual inputs gracefully"
    (let [empty-path (sut/->CommandMapPath [] {:type :root})
          mixed-keys (sut/->CommandMapPath ["string" :keyword 42 'symbol] {})]
      (is (= "root[root]" (sut/command-id empty-path)) "Empty path generates correct ID")
      (is (= [] (sut/command-path empty-path)) "Empty path is accessible")
      (is (= "root,string,:keyword,42,symbol" (sut/command-id mixed-keys))
          "Mixed key types in path generate correct ID")
      (is (= ["string" :keyword 42 'symbol] (sut/command-path mixed-keys)) "Mixed key types are preserved in path")))
  (testing "Hash consistency - equal objects have equal hashes"
    (let [cmd1 (sut/->CommandMapPath ["test"] {:data 1})
          cmd2 (sut/->CommandMapPath ["test"] {:data 2})
          cmd3 (sut/->CommandMapPath ["different"] {:data 1})]
      (is (= cmd1 cmd2) "Objects with same path are equal")
      (is (= (hash cmd1) (hash cmd2)) "Equal objects have same hash")
      (is (not= cmd1 cmd3) "Objects with different paths are not equal")
      (is (not= (hash cmd1) (hash cmd3)) "Unequal objects have different hashes")))
  (testing "Collection behavior edge cases - comprehensive set/map operations"
    (let [cmd1 (sut/->CommandMapPath ["a"] {})
          cmd2 (sut/->CommandMapPath ["a"] {:different "data"})
          cmd3 (sut/->CommandMapPath ["b"] {})]
      (is (= #{cmd1} (conj #{cmd1} cmd2))
          "Set deduplication based on path equality, set treats equal-path objects as identical")
      (is (= {cmd1 :value}
             (-> {}
                 (assoc cmd1 :old)
                 (assoc cmd2 :value)))
          "Map keys are replaced for equal-path objects")
      (is (= #{cmd1 cmd3} (set/union #{cmd1} #{cmd2 cmd3})) "Union deduplicates equal-path objects"))))
