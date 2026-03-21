<div align="center">
<h1>Query DSL</h1>
</div>

The Commando Query DSL is a built-in, lightweight query mechanism. It serves as a simple alternative to more comprehensive solutions like GraphQL or Pathom3, but it is much simpler and requires you to define dependency resolution manually.

Its primary purpose is to provide a way to:

1. **Selectively query data, returning only the fields they request.**

2. **Handle nested data dependencies through lazy-loading resolvers.**

## Content

- [Core Concept](#core-concept)
- [Workaround](#workaround)
- [Lazy Resolution](#lazy-resolution)
  - [Lazy Resolution Examples](#lazy-resolution-examples) 
- [Parameterization and Overriding](#parameterization-and-overriding)
  - [Parametrization Examples](#parametrization-examples)
- [Real-World Example](#real-world-example)
  - [Examples Queries](#examples-queries)
- [Advanced Topics](#advanced-topics)
	- [Combining Mutations and Queries](#combining-mutations-and-queries)
	- [Working with JSON](#working-with-json)
- [Summary](#summary)	

## Core Concept

The Query DSL is enabled by adding `commando.commands.query-dsl/command-resolve-spec` to commando execute registry.

You define your data "endpoints" by creating new methods for the `commando.commands.query-dsl/command-resolve` multimethod.

**The `QueryExpression` and `->query-run`**

A resolver's job is to return a map(or sequence) of data. The client passes a `QueryExpression` to specify which keys from that map they want. The QueryExpression is a simple, EQL-inspired vector.

You use the `commands-query-dsl/->query-run` function to filter your resolver's resulting map against the client's QueryExpression.

Here is the "Hello, World!" of the Query DSL:

```clojure
(require '[commando.core :as commando])
(require '[commando.commands.query-dsl :as query-dsl])

;; Define a resolver for :resolve-user
(defmethod query-dsl/command-resolve :resolve-user [_ {:keys [QueryExpression]}]
  ;; This map is the "full" data available
  (-> {:first-name "Adam"
	   :last-name "Nowak"
	   :info {:age 25
			  :passport {:number "FE123123"}}}
	;; ->query-run filters the map based on the QueryExpression
	(query-dsl/->query-run QueryExpression)))

;; Execute the command
(commando/execute
  [query-dsl/command-resolve-spec]
  {:commando/resolve :resolve-user
   :QueryExpression
   [:first-name        ;; Request :first-name
	{:info             ;; Request :info
	 [:passport]}]})   ;; and from :info, request :passport
;; =>
;; {:status :ok,
;;  :instruction
;;  {:first-name "Adam",
;;   :info {:passport
;;          {:number "FE123123"}}}}
```

Notice that `:last-name` and `:info {:age ...}` are not returned. The `->query-run` function processed the `QueryExpression` and returned only the requested keys.

## Workaround

To simplify examples, we define a small helper function `execute-with-registry` that sets up the command registry with the necessary Query DSL and built-in commands.

```clojure
(require 'commando.commands.builtin)
(require '[commando.commands.query-dsl :as query-dsl])

(defn execute-with-registry [instruction]
  (:instruction
   (commando.core/execute
	 [query-dsl/command-resolve-spec
	  commando.commands.builtin/command-fn-spec
	  commando.commands.builtin/command-from-spec]
	 instruction)))
```

## Lazy Resolution

What if a field is expensive to compute and not always needed? Instead of putting the data directly in the map, you can insert a **resolver object**.

A resolver object that holds:

1. A **default value**: Returned if the key is requested, but not queried into.
2. A **resolver function/instruction**: Executed only if the client provides a sub-query for that key.

There are several types of resolver constructors:

- `query-dsl/resolve-fn`: Lazily runs an arbitrary function.

- `query-dsl/resolve-instruction`: Lazily runs any Commando instruction (e.g., a mutation/fn/macro/from ... any).

- `query-dsl/resolve-instruction-qe`: Lazily runs another `:commando/resolve` command, allowing for nested Query DSL queries. This is the most common way to link resolvers.


### Lazy Resolution Examples

```clojure
(defmethod query-dsl/command-resolve :test-instruction-qe [_ {:keys [x QueryExpression]}]
  (let [x (or x 10)]
	(-> {;; ================================================================
		 ;; ordinary data
		 ;; ================================================================
		 :string "Value"

		 :map {:a
			   {:b {:c x}
				:d {:c x
					:f x}}}

		 :coll [{:a
				 {:b {:c x}
				  :d {:c x
					  :f x}}}
				{:a
				 {:b {:c x}
				  :d {:c x
					  :f x}}}]

		 ;; ================================================================
		 ;; resolve-fn examples
		 ;; ================================================================

		 :resolve-fn (query-dsl/resolve-fn "default value for resolve-fn"
					   (fn [{:keys [x]}]
						 (let [x (or x 1)]
						  {:a
						   {:b {:c x}
							:d {:c x
								:f x}}})))

		 :resolve-fn-of-colls (query-dsl/resolve-fn "default value for resolve-fn"
								(fn [{:keys [x]}]
								  (let [x (or x 1)]
									(for [y (range 0 10)]
									  {:a
									   {:b {:c (+ y x)}
										:d {:c (+ y x)
											:f (+ y x)}}}))))

		 :colls-of-resolve-fn (for [y (range 10)]
								(query-dsl/resolve-fn "default value for resolve-fn-call"
								  (fn [{:keys [x]}]
									(let [x (or x 1)]
									  {:a
									   {:b {:c (+ y x)}
										:d {:c (+ y x)
											:f (+ y x)}}}))))

		 ;; ================================================================
		 ;; resolve-instruction examples
		 ;; ================================================================

		 :resolve-instruction (query-dsl/resolve-instruction "default value for resolve-instruction"
								{:value-x 1
								 :result {:commando/fn (fn [& [value]]
														 {:a
														  {:b {:c value}
														   :d {:c (inc value)
															   :f (inc (inc value))}}})
										  :args [{:commando/from [:value-x]}]}})

		 ;; ================================================================
		 ;; resolve-instruction-qe examples
		 ;; ================================================================


		 :resolve-instruction-qe (query-dsl/resolve-instruction-qe "default value for resolve-instruction-qe"
								   {:commando/resolve :test-instruction-qe
									:x 1})
		 :resolve-instruction-qe-of-coll (query-dsl/resolve-instruction-qe "default value for resolve-instruction-qe"
										(vec
										  (for [x (range 5)]
											{:commando/resolve :test-instruction-qe
											 :x x})))
		 :coll-of-resolve-instruction-qe (for [x (range 5)]
										   (query-dsl/resolve-instruction-qe "default value for resolve-instruction-qe"
											 {:commando/resolve :test-instruction-qe
											  :x x}))}
	  (query-dsl/->query-run QueryExpression))))
```

#### Query 1: Querying ordinary data

Here, we simply query for ordinary data keys (`:string`, `:map`, `:coll`). No lazy resolvers are triggered.

- `:string` key returns a simple string value.

- `:map` key returns a nested map, trimmed to requrested sub-query `[:a [:b]]`.

- `:coll` key returns a vector of maps, each trimmed to the requested sub-query `[:a [:b]]`. From the side of QueryExpression no difference between a single map or a collection of maps, both are queried the same way.

```clojure
(execute-with-registry
  {:commando/resolve :test-instruction-qe
   :x 20
   :QueryExpression
   [:string
	{:map
	 [{:a
	   [:b]}]}
	{:coll
	 [{:a
	   [:b]}]}]})
;; =>
;; {:string "Value",
;;  :map {:a {:b {:c 20}}},
;;  :coll [{:a {:b {:c 20}}}
;;         {:a {:b {:c 20}}}]}
```

#### Query 2: Querying for Default Values

If we ask for the lazy keys (`:resolve-fn`, `:resolve-instruction-qe`, etc.) _without_ providing a _sub-query_, we get their default values.

```clojure
(execute-with-registry
  {:commando/resolve :test-instruction-qe
   :x 1
   :QueryExpression
   [:string
	:map
	:coll
	:resolve-fn
	:resolve-fn-of-colls
	:colls-of-resolve-fn
	:resolve-instruction
	:resolve-instruction-qe
	:resolve-instruction-qe-of-coll
	:coll-of-resolve-instruction-qe
	]})
;; =>
;; {:string "Value",
;;  :map  {:a {:b {:c 1}, :d {:c 1, :f 1}}},
;;  :coll [{:a {:b {:c 1}}}
;;         {:a {:b {:c 1}}}]
;;  :resolve-fn "default value for resolve-fn",
;;  :resolve-fn-of-colls "default value for resolve-fn"
;;  :colls-of-resolve-fn
;;  ["default value for resolve-fn-call"
;;   "default value for resolve-fn-call"
;;   "default value for resolve-fn-call"
;;   "default value for resolve-fn-call"
;;   "default value for resolve-fn-call"
;;   "default value for resolve-fn-call"
;;   "default value for resolve-fn-call"
;;   "default value for resolve-fn-call"
;;   "default value for resolve-fn-call"
;;   "default value for resolve-fn-call"]
;;  :resolve-instruction "default value for resolve-instruction"
;;  :resolve-instruction-qe "default value for resolve-instruction-qe"
;;  :resolve-instruction-qe-of-coll "default value for resolve-instruction-qe"
;;  :coll-of-resolve-instruction-qe
;;  ["default value for resolve-instruction-qe"
;;   "default value for resolve-instruction-qe"
;;   "default value for resolve-instruction-qe"
;;   "default value for resolve-instruction-qe"
;;   "default value for resolve-instruction-qe"]}
```

#### Query 3: Triggering Lazy Resolvers

Now, if we provide a _sub-query_ for a lazy key, the DSL will execute the resolver and then use the sub-query to _filter its result_.

Here we provide a sub-query `[{:a [:b]}]` for `:resolve-fn`. This triggers the function, and we get the resolved data back, filtered.

```clojure
(execute-with-registry
  {:commando/resolve :test-instruction-qe
   :x 20
   :QueryExpression
   [{:resolve-fn
	 [{:a
	   [:b]}]}]})
;; =>
;; {:resolve-fn {:a {:b {:c 1}}}}
```

The same applies to `resolve-instruction-qe`. Here, we trigger a nested, recursive call to `:test-instruction-qe`.

```clojure
(execute-with-registry
  {:commando/resolve :test-instruction-qe
   :x 20
   :QueryExpression
   [{:resolve-instruction-qe
	 [{:map [{:a [:b]}]}]}]})
;; =>
;; {:resolve-instruction-qe {:map {:a {:b {:c 1}}}}}
```

This recursive/nested resolution is the key to building relationships between your data.


## Parameterization and Overriding

How do you pass parameters to a nested resolver? You can "parameterize" a key in the QueryExpression using the `[<key> {<params-map>}]` syntax.

These parameters are passed to the resolver function (`resolve-fn`) or merged into the instruction map (`resolve-instruction`, `resolve-instruction-qe`).

This also allows a client to override parameters that might have been set by a parent resolver.

### Parametrization Examples

Let's look at a simple resolver and how we can override its parameters.

```clojure
(defmethod query-dsl/command-resolve :query/mixed-data [_ {:keys [x QueryExpression]}]
  (->
   [{:a {:b {:c x}
		 :d {:c (inc x) :f (dec x)}}}
	{:a {:b {:c x}
		 :d {:c (inc x) :f (dec x)}}}
	{:a {:b {:c x}
		 :d {:c (inc x) :f (dec x)}}}]
   (query-dsl/->query-run QueryExpression)))

(defmethod query-dsl/command-resolve :query/top-level [_ {:keys [x QueryExpression]}]
  (let [x (or x 10)]
	(-> {:string "value"

		 :map {:a
			   {:b {:c x}
				:d {:c x
					:f x}}}

		 :mixed-data (query-dsl/resolve-instruction-qe
					   ;; default value
					   []
					   ;; instruction to run
					   {:commando/resolve :query/mixed-data
						:x x})}
	  (query-dsl/->query-run QueryExpression))))
```

#### Query 1: Query default values

Asking for `:mixed-data` but don't query into it. We get the default value (an empty vector []).

```clojure
(execute-with-registry
  {:commando/resolve :query/top-level
   :x 1
   :QueryExpression
   [:string
	:mixed-data]})
;; =>
;; {:string "value",
;;  :mixed-data []}
```

#### Query 2: Nested Resolution with using Sub-Query

Now, we provide a sub-query for `:mixed-data`. This triggers the `resolve-instruction-qe`, which calls `:query/mixed-data`. The `:x 1` from the top-level(our query) instruction is passed down.

```clojure
(execute-with-registry
  {:commando/resolve :query/top-level
   :x 1
   :QueryExpression
   [:string
	{:mixed-data
	 [{:a
	   [{:b
		 [:c]}]}]}]})
;; =>
;; {:string "value",
;;  :mixed-data
;;  [{:a {:b {:c 1}}}
;;   {:a {:b {:c 1}}}
;;   {:a {:b {:c 1}}}]}
```

#### Query 3: Sub-Query with Overriding Parameters

Finally, we use the `[<key> {<params>}]` syntax. The QueryExpression (`[:mixed-data {:x 1000}]`) itself provides a new value for `:x 1000` just for the resolver under `:mixed-data` key. This new parameter map is merged with the _instruction_ inside the `query-dsl/resolve-instruction-qe` , overriding the original `:x 1`.

```clojure
(execute-with-registry
  {:commando/resolve :query/top-level
   :x 1
   :QueryExpression
   [:string
	{[:mixed-data {:x 1000}]  ;; <--- Parameter override
	 [{:a
	   [{:b
		 [:c]}]}]}]})
;; =>
;; {:string "value",
;;  :mixed-data
;;  [{:a {:b {:c 1000}}}
;;   {:a {:b {:c 1000}}}
;;   {:a {:b {:c 1000}}}]}
```

## Real-World Example

Let's combine these concepts. Assume we have a "database" of cars and emission standards.

```clojure
(defn db []
  {:emission-standard
   [{:id "Euro 6" :year_from "2014"}
	{:id "Zero Emission" :year_from "NaN"}]
   :cars
   [{:id "1",
	 :make "Tesla",
	 :model "Model 3",
	 :details {:year 2023,
			   :engine {:type "Electric", :horsepower 283},
			   :eco_standard "Zero Emission"},
	 :price_usd 45000}
	{:id "2",
	 :make "Toyota",
	 :model "Camry",
	 :details {:year 2022,
			   :engine {:type "Gasoline", :horsepower 208},
			   :eco_standard "Euro 6"},
	 :price_usd 26000}
	{:id "3",
	 :make "Ford",
	 :model "F-150",
	 :details {:year 2024,
			   :engine {:type "Gasoline", :horsepower 400},
			   :eco_standard "Euro 6"},
	 :price_usd 35000}
	{:id "4",
	 :make "BMW",
	 :model "X5",
	 :details {:year 2023,
			   :engine {:type "Hybrid", :horsepower 389},
			   :eco_standard "Euro 6"},
	 :price_usd 65000}
	{:id "5",
	 :make "Honda",
	 :model "Civic",
	 :details {:year 2022,
			   :engine {:type "Gasoline", :displacement_l 200},
			   :eco_standard "Euro 6"},
	 :price_usd 23000}]})
```

Now, let's define three resolvers:

1. `:eco_standard-by-id`: Fetches a standard from the "db".

2. `:car-by-id`: Fetches a single car. Notice how it replaces the :eco_standard ID with a lazy resolve-instruction-qe pointing to our other resolver. This is manual dependency resolution.

3. `:car-id-range`:  Fetches a list of cars. It fans out the work by mapping a list of IDs to a list of resolve-instruction-qe objects, each one calling :car-by-id.


```clojure
(defmethod query-dsl/command-resolve :eco_standard-by-id [_ {:keys [eco_standard-id QueryExpression]}]
  (when-let [emission-standard (first (filter #(= eco_standard-id (:id %)) (get (db) :emission-standard)))]
	(-> emission-standard
	  (query-dsl/->query-run QueryExpression))))

(defmethod query-dsl/command-resolve :car-by-id [_ {:keys [car-id engine-as-string? QueryExpression]}]
  (when-let [car-entity (first (filter #(= car-id (:id %)) (get (db) :cars)))]
	;; We modify the car entity before returning it
	(cond-> car-entity
	  ;; Replace the :eco_standard string with a lazy resolver
	  true (update-in [:details :eco_standard]
			 (fn [eco_standard-id]
			   ;; (resolve-instruction-qe takes <default-value>, <inner Instruction>)
			   ;; If the user will ask about keys inside :eco_standard,
			   ;; this inner Instruction will be executed automatically.
			   (query-dsl/resolve-instruction-qe eco_standard-id
				 {:commando/resolve :eco_standard-by-id
				  :eco_standard-id eco_standard-id})))
	  ;; Conditionally modify data based on a parameter
	  engine-as-string? (update-in [:details :engine] (fn [e] (pr-str e)))
	  ;; Filter the final result
	  true (query-dsl/->query-run QueryExpression))))

(defmethod query-dsl/command-resolve :car-id-range [_ {:keys [ids-to-query QueryExpression]}]
  (as-> (set ids-to-query) <>
	(keep (fn [{:keys [id]}] (when (contains? <> id) id)) (get (db) :cars))
	{:car-id-range (mapv
					 (fn [car-id]
					   ;; For each ID, return a lazy resolver for that car
					   (query-dsl/resolve-instruction-qe car-id
						 {:commando/resolve :car-by-id
				  :car-id car-id})) <>)}
	(query-dsl/->query-run <> QueryExpression)))
```

we used our `execute-with-registry` to make querying easier:

### Examples Queries

#### Query 1: Top-Level Query Only

We query for :car-id-range but do not provide a sub-query. The resolver runs, but the nested resolve-instruction-qe calls do not. We get their default values (the car-id strings).

```clojure
(execute-with-registry
  {:commando/resolve :car-id-range
   :ids-to-query ["2" "4" "100"]
   :QueryExpression
   [:car-id-range]})
;; RETURN =>
;; {:car-id-range ["2" "4"]}
```

#### Query 2: Nested Query

Now we provide a sub-query for `:car-id-range`:

- This triggers the list of `:car-by-id` resolvers.

- Each `:car-by-id` resolver runs.

- We query for `:details :eco_standard`, but we don't query into it.

- Therefore, we get the default value for `:eco_standard` (the `eco_standard-id` string, "Euro 6").

```clojure
(execute-with-registry
  {:commando/resolve :car-id-range
   :ids-to-query ["2" "4" "100"]
   :QueryExpression
   [{:car-id-range
	 [:make
	  :model
	  {:details
	   [:eco_standard
		{:engine
		 [:horsepower]}]}]}]})
;; RETURN =>
;; {:car-id-range
;;  [{:make "Toyota",
;;    :model "Camry",
;;    :details {:eco_standard "Euro 6", :engine {:horsepower 208}}}
;;   {:make "BMW",
;;    :model "X5",
;;    :details {:eco_standard "Euro 6", :engine {:horsepower 389}}}]}
```

#### Query 3: Parameterized and Deeply Nested Query

Now, we use parameterization to modify the behavior of nested resolvers.

1. `[[:car-id-range {:engine-as-string? true}]]`: We pass the `:engine-as-string?` parameter to the `:car-id-range` resolver, which in turn passes it to each `:car-by-id` resolver. You can see the :engine map is now a string.

2. `[[:eco_standard {:eco_standard-id "Zero Emission"}]]`: We provide a sub-query and an override parameter for `:eco_standard`. This triggers the `:eco_standard-by-id` resolver and overrides its ID, forcing it to return "Zero Emission" for both cars.

```clojure
(execute-with-registry
  {:commando/resolve :car-id-range
   :ids-to-query ["2" "4" "100"]
   :QueryExpression
   [{[:car-id-range {:engine-as-string? true}]
	 [:make
	  :model
	  {:details
	   [{[:eco_standard {:eco_standard-id "Zero Emission"}]
	 [:id
	  :year_from]}
	:engine]}]}]})
;; RETURN =>
;; {:car-id-range
;;  [{:make "Toyota",
;;    :model "Camry",
;;    :details
;;    {:eco_standard {:id "Zero Emission", :year_from "NaN"},
;;     :engine "{:type \"Gasoline\", :horsepower 208}"}}
;;   {:make "BMW",
;;    :model "X5",
;;    :details
;;    {:eco_standard {:id "Zero Emission", :year_from "NaN"},
;;     :engine "{:type \"Hybrid\", :horsepower 389}"}}]}
```

## Advanced Topics

### Combining Mutations and Queries

Because the Query DSL is built on Commando, you can easily combine it with other commands, like mutations or `:commando/from`, in a single `execute` call.

```clojure
(commando.core/execute
 [commando.commands.query-dsl/command-resolve-spec
  commando.commands.builtin/command-mutation-spec
  commando.commands.builtin/command-from-spec]
 {"client-that-want-buy-a-car"
  {:commando/resolve :find-user-by-login :login "adam12N"}
  "car-client-want-to-buy"
  {:commando/resolve :car-by-id
   :id "2"
   :engine-as-string? true
   :QueryExpression
   [:make
	:model
	{:details
	 [{[:eco_standard {:eco_standard-id "Zero Emission"}]
	   [:id
	:year_from]}
	  :engine]}]}}
 "transaction"
 {:commando/mutation :car-sell-agreement
  :car {:commando/from ["car-client-want-to-buy"]}
  :client {:commando/from "client-that-want-buy-a-car"}
  :option/discount "5%"
  :option/credit false
  :option/color "crystal red"})
```

### Working with Strings

To work with JSON input (e.g. from an HTTP request), use string keys to describe Instructions (instead `:commando/resolve` use `"commando-resolve"`) and QueryExpressions(also with string keys).

Note that defmethod dispatches on a string ("instant-car-model") and the parameters map (:strs [QueryExpression]) uses string-based destructuring. Cause QueryExpression uses strings, the resolvers must also use string keys in their returned maps.

```clojure
(defmethod query-dsl/command-resolve "instant-car-model" [_ {:strs [QueryExpression]}]
  (query-dsl/->query-run
	{"id" "4",
	 "make" "BMW",
	 "model" "X5",
	 "details" {"year" 2023,
		"engine" {"type" "Hybrid", "horsepower" 389},
		"eco_standard" "Euro 6"},
	 "price_usd" 65000}
	QueryExpression))

(commando.core/execute
  [commando.commands.query-dsl/command-resolve-spec]
  (clojure.data.json/read-str
	"{\"commando-resolve\":\"instant-car-model\",
	  \"QueryExpression\":
	  [\"make\",
	   \"model\",
	   {\"details\":
		[{\"engine\":
		  [\"horsepower\"]}]}]}"))

;; =>
{"make" "BMW",
 "model" "X5",
 "details"
 {"engine"
  {"horsepower" 389}}}
```

## Summary

This DSL is designed for advanced users familiar with Clojure and the Commando library. The structure is intentionally simple to encourage custom resolver logic and composability. For a full overview of commands and concepts, see the main [README](../README.md) file.
