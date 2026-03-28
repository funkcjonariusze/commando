(ns commando.impl.pathtrie-test
  (:require
   #?(:cljs [cljs.test :refer [deftest is testing]]
      :clj [clojure.test :refer [deftest is testing]])
   [commando.impl.command-map  :as cm]
   [commando.impl.pathtrie     :as pathtrie]))

;; -----------
;; Trie operations test
;; -----------

(deftest trie-operations-test
  (testing "Incremental trie update matches full rebuild"
    (let [cmd-a (cm/->CommandMapPath [:a] {:type :test})
          cmd-b (cm/->CommandMapPath [:b] {:type :test})
          cmd-c (cm/->CommandMapPath [:c :d] {:type :test})
          cmd-e (cm/->CommandMapPath [:a :x] {:type :test})
          original-cmds [cmd-a cmd-b cmd-c]
          original-trie (pathtrie/build-path-trie original-cmds)
          ;; Remove :a subtree, insert cmd-e
          updated-trie (-> original-trie
                         (pathtrie/trie-remove-paths [[:a]])
                         (pathtrie/trie-insert-commands [cmd-e]))
          full-rebuild (pathtrie/build-path-trie [cmd-b cmd-c cmd-e])]
      (is (= updated-trie full-rebuild)
          "Incremental trie update produces same result as full rebuild"))))
