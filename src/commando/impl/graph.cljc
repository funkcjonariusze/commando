(ns commando.impl.graph
  (:require
   [clojure.set :as set]))

(defn ^:private no-incoming
  "Returns the set of nodes in graph g for which there are no incoming
  edges, where g is a map of nodes to sets of nodes."
  [g]
  (let [nodes (set (keys g)) have-incoming (apply set/union (vals g))] (set/difference nodes have-incoming)))

(defn topological-sort
  "Sort for acyclic directed graph g (using khan algo).
   Where g is a map of nodes to sets of nodes.
   If g contains cycles, it orders the acyclic parts and leaves cyclic parts as is.
   Returns a map with :sorted containing result and :cyclic containing cycles if exists"
  ([g] (topological-sort g [] (no-incoming g)))
  ([g l s]
   (if (empty? s)
     (if (every? empty? (vals g))
       {:sorted l}
       {:sorted l
        :cyclic (filter (fn [[_ v]] (seq v)) g)})
     (let [[n s'] [(first s) (rest s)]
           m (g n)
           g' (assoc g n #{})
           new-nodes (set/intersection (no-incoming g') m)
           s'' (set/union s' new-nodes)]
       (recur g' (conj l n) s'')))))
