# Commando Query DSL

This mechanism positions itself as a built-in, lightweight, and somewhat limited alternative to GraphQL or Pathom3 (Pathom3 is a data query and transformation library for Clojure). Commando DSL is much more primitive and requires you to describe dependency resolution yourself. Let's look at how it works.


## Example Database Setup

```clojure
(require '[commando.core :as commando])
(require '[commando.commands.builtin :as commands-builtin])
(require '[commando.commands.query-dsl :as commands-query-dsl])

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


## Namespace and Resolver

The namespace `commando.commands.query-dsl` exposes the `command-resolve-spec` command, which is extended with the multimethod `command-resolve`. Compared to mutations via `commando.core/command-mutation-spec`, this approach is focused on query expressions and their return values.

### QueryExpression

A `QueryExpression` is a simplified structure inspired by EQL (Extensible Query Language), designed for passing through so-called resolvers and returning only the requested data keys.

```clojure
(require '[commando.core :as commando])
(require '[commando.commands.query-dsl :as commands-query-dsl])

(defmethod commands-query-dsl/command-resolve :resolve-user [_ {:keys [QueryExpression]}]
  (-> {:first-name "Adam"
       :last-name "Nowak"
       :info {:age 25
	      :passport {:number "FE123123"}}}
    (->query-run QueryExpression)))

(commando/execute
  [commands-query-dsl/command-resolve-spec]
  {:commando/resolve :resolve-user
   :QueryExpression
   [:first-name
    {:info
     [:passport]}]})

;; RETURN => 
;; {:status :ok,
;;  :instruction
;;  {:first-name "Adam",
;;   :info {:passport
;;          {:number "FE123123"}}}}
```

Notice that `:last-name` is not returned, as `->query-run` only returns the requested keys.

- `commands-query-dsl/->query-run` receives a `QueryExpression` and determines what to return to the user as a result.
- `commands-query-dsl/query-resolve` is an object constructor that will be processed by `->query-run`. It takes two arguments: the default value and an inner instruction.


## Example Query Resolvers

Let's look at a more complex example:

```clojure
(defmethod commands-query-dsl/command-resolve :eco_standard-by-id [_ {:keys [eco_standard-id QueryExpression]}]
  (when-let [emission-standard (first (filter #(= eco_standard-id (:id %)) (get (db) :emission-standard)))]
	(-> emission-standard
	  (commands-query-dsl/->query-run QueryExpression))))

(defmethod commands-query-dsl/command-resolve :car-by-id [_ {:keys [car-id engine-as-string? QueryExpression]}]
  (when-let [car-entity (first (filter #(= car-id (:id %)) (get (db) :cars)))]
	(cond-> car-entity
	  true (update-in [:details :eco_standard] (fn [eco_standard-id]
												 ;; (query-resolve take two arg: <default-value>, <inner Instruction>)
												 ;; If the user will ask about keys inside the :eco_standard
												 ;; inner Instruction will be executed automatically.
												 (commands-query-dsl/query-resolve eco_standard-id
												   {:commando/resolve :eco_standard-by-id
													:eco_standard-id eco_standard-id})))
	  engine-as-string? (update-in [:details :engine] (fn [e] (pr-str e)))
	  true (commands-query-dsl/->query-run QueryExpression))))

(defmethod commands-query-dsl/command-resolve :car-id-range [_ {:keys [ids-to-query QueryExpression]}]
  (as-> (set ids-to-query) <>
	(keep (fn [{:keys [id]}] (when (contains? <> id) id)) (get (db) :cars))
	{:car-id-range (mapv
					 (fn [car-id]
					   (commands-query-dsl/query-resolve car-id
						 {:commando/resolve :car-by-id
						  :car-id car-id})) <>)}
	(commands-query-dsl/->query-run <> QueryExpression)))
```	

To execute a query, use the following shortcut function:
	
```clojure
(defn query [instruction-map]
  (commando/execute
   [commands-query-dsl/command-resolve-spec]
   instruction-map))))
```

## Sample Queries

Let's make a query for three resolvers. On the top level, we write a resolver that filters the list to the IDs we want:

```clojure
(query
  {:commando/resolve :car-id-range
   :ids-to-query ["2" "4" "100"]
   :QueryExpression
   [:car-id-range]})
;; RETURN =>
;; {:car-id-range ["2" "4"]}
```

When we specify which keys we want in `QueryExpression`, the resolver `:car-by-id` is triggered for each ID and returns only the requested fields:

```clojure
(query
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

### Parameterization

QueryExpression supports parameterization at the declaration level. To override configuration, you can pass parameters for specific resolvers.

```clojure
[:car]
[{:car
  [:make
   :model]}]
;; With added params =>
[[:car {:SOME-KEY-PASSED-TO-RESOLVER true}]]
[{[:car {:SOME-KEY-PASSED-TO-RESOLVER true}]
  [:make
   :model]}]
```


Parameters are passed only through keys defined for a specific resolver via `commando-query/command-resolve`.

For example, we add an optional parameter `:engine-as-string?` for serializing the `:engine` key.

```clojure
(query
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

Because the mechanism is limited only by the CommandMapSpec (`commando.toolbox.graph-query-dsl/command-resolve-spec`), you can easily combine it with other mutation commands, etc.:

```clojure
(commando.core/execute
 [commands-query-dsl/command-resolve-spec
  commands-builtin/command-mutation-spec
  commands-builtin/command-from-spec]
 {"client-that-want-buy-a-car"
  {:commando/resolve :find-user-by-login "adam12N"}
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

## Working with JSON

To work with JSON values, use the `command-resolve-json-spec` command.

```clojure
(defmethod command-resolve "instant-car-model" [_ {:strs [QueryExpression]}]
  (->query-run
	{"id" "4",
	 "make" "BMW",
	 "model" "X5",
	 "details" {"year" 2023,
				"engine" {"type" "Hybrid", "horsepower" 389},
				"eco_standard" "Euro 6"},
	 "price_usd" 65000}
	QueryExpression))

(commando.core/execute
  [commands-query-dsl/command-resolve-json-spec]
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

This DSL is designed for advanced users familiar with Clojure and the Commando library. The structure is intentionally simple to encourage custom resolver logic and composability. For a full overview of commands and concepts, see the main README file
