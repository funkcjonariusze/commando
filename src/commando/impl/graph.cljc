(ns commando.impl.graph
  (:require
   [clojure.set :as set]))

(defn topological-sort
  "Efficiently sorts a directed acyclic graph using Kahn's algorithm with in-degree counting.
   'g' is a map of nodes to sequences of their dependencies.
   Returns a map with :sorted containing the topologically sorted list of nodes,
   and :cyclic containing the remaining nodes if a cycle is detected."
  [g]
  (let [;; Build the reverse graph to easily find dependents and collect all nodes.
        rev-g (reduce-kv (fn [acc k vs]
                           (reduce (fn [a v] (update a v (fnil conj []) k)) acc vs))
                {} g)
        all-nodes (set/union (set (keys g)) (set (keys rev-g)))

        ;; calculate in-degrees for all nodes.
        in-degrees (reduce-kv (fn [acc node deps]
                                (assoc acc node (count deps)))
                     {} g)

        ;; Initialize the queue with nodes that have no incoming edges.
        ;; Using a vector as a FIFO queue.
        q (reduce (fn [queue node]
                    (if (zero? (get in-degrees node 0))
                      (conj queue node)
                      queue))
            [] all-nodes)]
    (loop [queue q
           sorted-result []
           degrees in-degrees]
      (if-let [node (first queue)]
        (let [dependents (get rev-g node [])
              ;; Reduce in-degree for all dependents
              ;; and find new nodes with zero in-degree.
              [next-degrees new-zero-nodes]
              (reduce (fn [[degs zeros] dep]
                        (let [new-degree (dec (get degs dep))]
                          [(assoc degs dep new-degree)
                           (if (zero? new-degree) (conj zeros dep) zeros)]))
                      [degrees []]
                      dependents)]
          (recur (into (subvec queue 1) new-zero-nodes)
                 (conj sorted-result node)
                 next-degrees))
        (if (= (count sorted-result) (count all-nodes))
          {:sorted sorted-result :cyclic {}}
          (let [cyclic-nodes (->> degrees
                                  (filter (fn [[_ v]] (pos? v)))
                                  (into {}))]
            {:sorted sorted-result :cyclic cyclic-nodes}))))))

