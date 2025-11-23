# Working with JSON

Since Commando is a technology that allows you to create your own DSLs, a critical aspect is the format of the data structures you process. JSON is a common choice for APIs, serialization, and database storage. Therefore, your DSL must be adaptable and work seamlessly outside the Clojure ecosystem.

## The Challenge: Keywords vs. Strings

Commando is idiomatic Clojure and heavily relies on namespaced keywords (e.g., `:commando/from`, `:commando/mutation`). JSON, however, does not support keywords; it only uses strings for object keys. This presents a challenge when an instruction needs to be represented in JSON format.

## The Solution: String-Based Commands

Commando's built-in commands are designed to work with string-based keys out of the box, allowing for seamless JSON interoperability. When parsing instructions, Commando recognizes both the keyword version (e.g., `:commando/mutation`) and its string counterpart (`"commando-mutation"`).

This allows you to define instructions in pure JSON, slurp in clojure, parse and have them executed by Commando.

### Example: Vector Dot Product

Imagine you want to calculate the scalar (dot) product of two vectors described in a JSON file.

**`vectors.json`:**
```json
{
  "vector-1": { "x": 1, "y": 2 },
  "vector-2": { "x": 4, "y": 5 },
  "scalar-product-value": {
    "commando-mutation": "dot-product",
    "v1": { "commando-from": ["vector-1"] },
    "v2": { "commando-from": ["vector-2"] }
  }
}
```

Notice the use of `"commando-mutation"` and `"commando-from"` as string keys.

To handle the custom `"dot-product"` mutation, you define a `defmethod` for `commando.commands.builtin/command-mutation` that dispatches on the string `"dot-product"`. When destructuring the parameters map, you must also use `:strs` to correctly access the string-keyed values (`v1`, `v2`).

```clojure
(require '[commando.commands.builtin :as commands-builtin]
         '[commando.core :as commando]
         '[clojure.data.json :as json])

;; Define the mutation handler for the "dot-product" string identifier
(defmethod commands-builtin/command-mutation "dot-product" [_ {:strs [v1 v2]}]
  (->> ["x" "y"]
       (map #(* (get v1 %) (get v2 %)))
       (reduce + 0)))

;; Read the JSON file and execute the instruction
(let [json-string (slurp "vectors.json")
      instruction (json/read-str json-string)]
  (commando/execute
    [commands-builtin/command-mutation-spec
     commands-builtin/command-from-spec]
    instruction))
```

When executed, Commando correctly resolves the dependencies and applies the mutation, producing the final instruction map:

```clojure
;; =>
{:status :ok
 :instruction
 {"vector-1" {"x" 1, "y" 2},
  "vector-2" {"x" 4, "y" 5},
  "scalar-product-value" 14}}
```

By supporting string-based keys for its commands, Commando makes it easy to build powerful, data-driven systems that can be defined and serialized using the ubiquitous JSON format. For more details on creating custom commands, see the [main README](../README.md).

## Important Note on String-Based Commands

It's important to understand that only a select few core commands have direct string-based equivalents for JSON interoperability. These are primarily:

*   `commando.commands.builtin/command-macro-spec` (`"commando-macro"`)
*   `commando.commands.builtin/command-from-spec` (`"commando-from"`)
*   `commando.commands.builtin/command-mutation-spec` (`"commando-mutation"`)
*   `commando.commands.query-dsl/command-resolve-spec` (`"commando-resolve"`)

Other commands, such as `:commando/apply` or `:commando/fn`, are more tightly coupled with Clojure's functional mechanisms and do not have direct string-based aliases.

### Leveraging `commando-macro-spec` for JSON Instructions

For scenarios where you need to define complex logic using string keys in JSON, but still want to utilize Clojure-specific commands, `commando-macro-spec` (with its string alias `"commando-macro"`) is your most powerful tool.

You can define a macro with a string identifier in your Clojure code, and within that macro's `defmethod`, you can use any Clojure-idiomatic commands (e.g., `:commando/apply`, `:commando/from`, or custom Clojure-based commands).

This allows you to declare high-level logic in your JSON instruction using string keys, while encapsulating the more intricate, Clojure-specific command structures within the macro definition. The macro acts as a bridge, expanding the JSON-friendly instruction into a full Clojure-based Commando instruction at runtime.

Here is a brief example illustrating the concept.

**JSON Instruction:**
```json
{
  "calculation-result": {
    "commando-macro": "calculate-and-format",
    "input-a": 10,
    "input-b": 25
  }
}
```

**Commando Macro Definition:**
```clojure
(require '[commando.commands.builtin :as commands-builtin])

(defmethod commands-builtin/command-macro "calculate-and-format"
  [_ {:strs [input-a input-b]}]
  ;; Inside the macro, we can use Clojure-native commands with keywords
  ;; to define the complex logic that will be expanded at runtime.
  {:= :formatted-output
   :commando/apply
   {:raw-result {:commando/fn (fn [& [a b]] (+ a b))
                 :args [input-a input-b]}
    :formatted-output {:commando/fn (fn [& args] (apply str args))
                       :args ["The result is: " {:commando/from [:commando/apply :raw-result]}]}}})
;; => "35"
```

In this example, the JSON file uses the string-based `"commando-macro"` to invoke `"calculate-and-format"`. The corresponding `defmethod` in Clojure takes the string inputs, then expands into a more complex instruction using keyword-based commands like `:commando/apply`, `:commando/fn`, and `:commando/from` to perform the actual logic.
