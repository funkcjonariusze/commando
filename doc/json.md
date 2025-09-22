# Working with JSON

Commando supports the idea of describing instructions using JSON structures. This is useful for storing, editing, and transporting command instructions, especially when interoperability between systems is required.

For example, imagine you want to calculate the scalar (dot) product of two vectors described as JSON:

```js
{"vector-1": {"x": 1, "y": 2},
 "vector-2": {"x": 4, "y": 5},
 "scalar-product-value":
  {"commando-mutation": "dot-product",
   "v1": {"commando-from": ["vector-1"]},
   "v2": {"commando-from": ["vector-2"]}}}
```

Since JSON does not support namespaced keywords like Clojure does, we use alternative built-in keys, replacing `commando/mutation` with `"commando-mutation"`. This allows Commando to parse and execute structured instructions from JSON as if they were native Clojure maps.

Let's declare a mutation handler for the `"commando-mutation"` command—a function that will help us obtain the scalar product of two vectors:

```clojure
(require '[commando.commands.builtin :as commands-builtin])

(defmethod commands-builtin/command-mutation "dot-product" [_ {:strs [v1 v2]}]
  (->> ["x" "y"]
       (map #(* (get v1 %) (get v2 %)))
       (reduce + 0)))
```

Now, let's see how the instruction looks in practice:

```clojure
(require '[commando.core :as commando])
(require '[commando.commands.builtin :as commands-builtin])

(commando/execute
  [commands-builtin/command-mutation-json-spec
   commands-builtin/command-from-json-spec]
  (clojure.data.json/read-str
    (slurp "vector-scalar.json")))
;; =>
{:instruction
 {"vector-1" {"x" 1, "y" 2},
  "vector-2" {"x" 4, "y" 5},
  "scalar-product-value" 14}}
```
