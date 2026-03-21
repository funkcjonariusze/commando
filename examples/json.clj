;; ┌─────────────────────────────────────────────────────┐
;; │ Commando + JSON                                     │
;; └─────────────────────────────────────────────────────┘
;;
;; Commando instructions are Clojure maps — but they don't
;; have to be written in Clojure. Because built-in commands
;; recognize both keyword keys (:commando/from) and string
;; keys ("commando-from"), you can author instructions in
;; plain JSON, parse them, and execute as-is.
;;
;; This walkthrough shows:
;; 1. Keywords vs. strings — what maps to what
;; 2. A JSON instruction executed from a file
;; 3. Using macros as a bridge to Clojure-only features
;;
;; Evaluate each form in your REPL to follow along.


;; ┌─────────────────────────────────────────────────────┐
;; │ 1. SETUP                                            │
;; └─────────────────────────────────────────────────────┘

(require '[commando.core             :as commando])
(require '[commando.commands.builtin :as builtin])
(require '[clojure.data.json         :as json])


;; ┌─────────────────────────────────────────────────────┐
;; │ 2. KEYWORDS VS. STRINGS                             │
;; └─────────────────────────────────────────────────────┘
;;
;; In Clojure you write:
;;
;;   {:commando/from [:greeting]}
;;
;; In JSON there are no keywords, so the equivalent is:
;;
;;   {"commando-from": ["greeting"]}
;;
;; Commando's built-in commands handle both forms.
;; The mapping:
;;
;;   Clojure keyword        JSON string
;;   ──────────────────     ──────────────────
;;   :commando/from         "commando-from"
;;   :commando/mutation     "commando-mutation"
;;   :commando/macro        "commando-macro"
;;   :=>                    "=>"
;;
;; Let's verify — same instruction, string keys:

(:instruction
 (commando/execute
   [builtin/command-from-spec]
   {"greeting" "hello"
    "copy"     {"commando-from" ["greeting"]}}))
;; => {"greeting" "hello", "copy" "hello"}

;; Drivers work too:

(:instruction
 (commando/execute
   [builtin/command-from-spec]
   {"user" {"name" "Bob" "age" 30}
    "name" {"commando-from" ["user"] "=>" ["get" "name"]}}))
;; => {"user" {"name" "Bob", "age" 30}, "name" "Bob"}


;; ┌─────────────────────────────────────────────────────┐
;; │ 3. EXECUTING A JSON FILE                            │
;; └─────────────────────────────────────────────────────┘
;;
;; Suppose you have a file `vectors.json`:
;;
;;   {
;;     "vector-1": { "x": 1, "y": 2 },
;;     "vector-2": { "x": 4, "y": 5 },
;;     "scalar-product": {
;;       "commando-mutation": "dot-product",
;;       "v1": { "commando-from": ["vector-1"] },
;;       "v2": { "commando-from": ["vector-2"] }
;;     }
;;   }
;;
;; First, define the mutation. Note the string dispatch
;; value "dot-product" and `:strs` destructuring — because
;; the keys coming from JSON are strings, not keywords.

(defmethod builtin/command-mutation "dot-product"
  [_ {:strs [v1 v2]}]
  (reduce + (map #(* (get v1 %) (get v2 %)) ["x" "y"])))

;; Now parse and execute:

(def json-instruction
  (json/read-str
    "{
       \"vector-1\": { \"x\": 1, \"y\": 2 },
       \"vector-2\": { \"x\": 4, \"y\": 5 },
       \"scalar-product\": {
         \"commando-mutation\": \"dot-product\",
         \"v1\": { \"commando-from\": [\"vector-1\"] },
         \"v2\": { \"commando-from\": [\"vector-2\"] }
       }
     }"))

(:instruction
 (commando/execute
   [builtin/command-mutation-spec
    builtin/command-from-spec]
   json-instruction))
;; => {"vector-1"       {"x" 1, "y" 2},
;;     "vector-2"       {"x" 4, "y" 5},
;;     "scalar-product" 14}
;;
;; 1*4 + 2*5 = 14. Correct.

;; In a real project it's just:
;;
;;   (-> (slurp "vectors.json")
;;       (json/read-str)
;;       (->> (commando/execute registry)))


;; ┌─────────────────────────────────────────────────────┐
;; │ 4. WHICH COMMANDS HAVE STRING ALIASES?              │
;; └─────────────────────────────────────────────────────┘
;;
;; Not every built-in command has a string counterpart.
;; The JSON-friendly ones:
;;
;;   "commando-from"      — reference another value
;;   "commando-mutation"  — side-effecting operation
;;   "commando-macro"     — expand a template
;;   "commando-context"   — injects external context
;;
;; These do NOT have string aliases (they need Clojure
;; features like functions or raw data):
;;
;;   :commando/fn          — calls a function
;;   :commando/apply       — returns data as-is
;;
;; This is intentional. JSON is data — it shouldn't
;; contain executable code. If you need computation
;; inside a JSON instruction, use a macro.


;; ┌─────────────────────────────────────────────────────┐
;; │ 5. MACROS AS A BRIDGE                               │
;; └─────────────────────────────────────────────────────┘
;;
;; A macro is the escape hatch from JSON into Clojure.
;; The JSON side declares *what* to do (string keys).
;; The macro expands into *how* to do it (keywords,
;; functions, anything Clojure can express).
;;
;; JSON instruction:
;;
;;   {
;;     "result": {
;;       "commando-macro": "add-and-format",
;;       "a": 10,
;;       "b": 25
;;     }
;;   }
;;
;; Clojure macro definition:

(defmethod builtin/command-macro "add-and-format"
  [_ {:strs [a b]}]
  ;; Inside the macro we have full Clojure — keywords,
  ;; functions, nested commands, anything.
  {:commando/fn str
   :args        [(+ a b)]})

(:instruction
 (commando/execute
   [builtin/command-macro-spec
    builtin/command-fn-spec]
   {"result" {"commando-macro" "add-and-format"
              "a" 10
              "b" 25}}))
;; => {"result" "35"}

;; The JSON author only needs to know the macro's name
;; and parameters. All Clojure-specific logic lives in
;; the defmethod — invisible to the JSON side.


;; ┌─────────────────────────────────────────────────────┐
;; │ 6. A LARGER EXAMPLE                                 │
;; └─────────────────────────────────────────────────────┘
;;
;; A reporting pipeline defined in JSON. Three mutations
;; chained via dependencies — fetch data, aggregate it,
;; format the output.

(defmethod builtin/command-mutation "fetch-sales"
  [_ {:strs [region]}]
  ;; Imagine a database query here
  {"region" region "units" 150 "revenue" 45000})

(defmethod builtin/command-mutation "aggregate"
  [_ {:strs [data]}]
  {"avg-price" (/ (get data "revenue") (get data "units"))
   "region"    (get data "region")})

(defmethod builtin/command-mutation "format-report"
  [_ {:strs [summary]}]
  (str "Region: "    (get summary "region")
       " | Avg: $"   (get summary "avg-price")
       " | Status: OK"))

(def report-json
  (json/read-str
    "{
       \"raw\":    { \"commando-mutation\": \"fetch-sales\",
                     \"region\": \"EU-West\" },
       \"stats\":  { \"commando-mutation\": \"aggregate\",
                     \"data\": { \"commando-from\": [\"raw\"] } },
       \"report\": { \"commando-mutation\": \"format-report\",
                     \"summary\": { \"commando-from\": [\"stats\"] } }
     }"))

(:instruction
 (commando/execute
   [builtin/command-mutation-spec
    builtin/command-from-spec]
   report-json))
;; => {"raw"    {"region" "EU-West", "units" 150, "revenue" 45000},
;;     "stats"  {"avg-price" 300, "region" "EU-West"},
;;     "report" "Region: EU-West | Avg: $300 | Status: OK"}
;;
;; Commando resolved the dependency chain automatically:
;;   "report" → "stats" → "raw"


;; ┌─────────────────────────────────────────────────────┐
;; │ SUMMARY                                             │
;; └─────────────────────────────────────────────────────┘
;;
;; • Built-in commands work with string keys — JSON
;;   instructions execute without any conversion.
;;
;; • Use "commando-from", "commando-mutation", and
;;   "commando-macro" as string-key equivalents.
;;
;; • Macros bridge JSON and Clojure: declare intent in
;;   JSON, implement logic in a defmethod.
;;
;; • Dependencies, drivers ("=>"), and validation all
;;   work identically with string keys.
