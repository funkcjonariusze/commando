(ns commando.impl.graph)

(defn topological-sort
  "Efficiently sorts a directed acyclic graph using Kahn's algorithm with in-degree counting.
   'g' is a map of nodes to sequences of their dependencies.
   Returns a map with :sorted containing the topologically sorted list of nodes,
   and :cyclic containing the remaining nodes if a cycle is detected."
  [g]
  (let [;; Build the reverse graph to easily find dependents and collect all nodes.
        rev-g (persistent!
                (reduce-kv (fn [acc k vs]
                             (reduce (fn [a v]
                                       (let [existing (get a v [])]
                                         (assoc! a v (conj existing k))))
                               acc vs))
                  (transient {}) g))
        node-count (count g)

        ;; calculate in-degrees for all nodes.
        in-degrees (persistent!
                     (reduce-kv (fn [acc node deps]
                                  (assoc! acc node (count deps)))
                       (transient {}) g))

        ;; Initialize the queue with nodes that have no incoming edges.
        ;; Using a vector as a FIFO queue.
        q (reduce-kv (fn [queue node deps]
                       (if (zero? (count deps))
                         (conj queue node)
                         queue))
            [] g)]
    (loop [queue q
           sorted-result (transient [])
           degrees (transient in-degrees)]
      (if-let [node (first queue)]
        (let [dependents (get rev-g node [])
              ;; Reduce in-degree for all dependents
              ;; and find new nodes with zero in-degree.
              [next-degrees new-zero-nodes]
              (reduce (fn [[degs zeros] dep]
                        (let [new-degree (dec (get degs dep))]
                          [(assoc! degs dep new-degree)
                           (if (zero? new-degree) (conj zeros dep) zeros)]))
                      [degrees []]
                      dependents)]
          (recur (into (subvec queue 1) new-zero-nodes)
                 (conj! sorted-result node)
                 next-degrees))
        (let [sorted (persistent! sorted-result)]
          (if (= (count sorted) node-count)
            {:sorted sorted :cyclic {}}
            (let [final-degrees (persistent! degrees)
                  cyclic-nodes (->> final-degrees
                                    (filter (fn [[_ v]] (pos? v)))
                                    (into {}))]
              {:sorted sorted :cyclic cyclic-nodes})))))))

