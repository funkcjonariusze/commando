<div align="center">
<img width="30%" src="./logo/Commando.png">
</div>

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.funkcjonariusze/commando.svg)](https://clojars.org/org.clojars.funkcjonariusze/commando)
[![Run tests](https://github.com/funkcjonariusze/commando/actions/workflows/unit_test.yml/badge.svg)](https://github.com/funkcjonariusze/commando/actions/workflows/unit_test.yml)
[![cljdoc badge](https://cljdoc.org/badge/org.clojars.funkcjonariusze/commando)](https://cljdoc.org/d/org.clojars.funkcjonariusze/commando/1.0.6)

**Commando** is a flexible Clojure library for managing, extracting, and transforming data inside nested map structures aimed to build your own Data DSL.

## Content

- [Installation](#installation)
- [Quick Start](#quick-start)
- [Concept](#concept)
- [Basics](#basics)
  - [Builtin Functionality](#builtin-functionality)
    - [command-from-spec](#command-from-spec)
    - [command-fn-spec](#command-fn-spec)
    - [command-apply-spec](#command-apply-spec)
    - [command-mutation-spec](#command-mutation-spec)
    - [command-macro-spec](#command-macro-spec)
    - [command-context-spec](#command-context-spec)
  - [Drivers (Post-Processing)](#drivers-post-processing)
    - [Built-in Drivers](#built-in-drivers)
    - [Pipeline](#pipeline)
    - [Custom Drivers](#custom-drivers)
  - [Adding New Commands](#adding-new-commands)
- [Status-Map and Internals](#status-map-and-internals)
  - [Configuring Execution Behavior](#configuring-execution-behavior)
    - [`:debug-result`](#debug-result)
    - [`:error-data-string`](#error-data-string)
  - [Performance](#performance)
- [Integrations](#integrations)
- [Versioning](#versioning)
- [License](#license)

## Installation

```clojure
;; deps.edn with git
{org.clojars.funkcjonariusze/commando {:mvn/version "1.0.6"}}

;; leiningen
[org.clojars.funkcjonariusze/commando "1.0.6"]
```

## Quick Start

```clojure
(require '[commando.core :as commando])
(require '[commando.commands.builtin :as commands-builtin])

(commando/execute
  [commands-builtin/command-from-spec]
  {"1" 1
   "2" {:commando/from ["1"]}
   "3" {:commando/from ["2"]}}))

;; RETURN =>
;;   {:instruction {"1" 1, "2" 1, "3" 1}
;;    :status :ok
;;    :errors []
;;    :warnings []
;;    :successes [{:message "All commands executed successfully"}]}
```
## Concept

The main idea of Commando is to create your own flexible, data-driven DSL. Commando enables you to describe complex data transformation and integration pipelines declaratively, tying together data sources, migrations, DTOs, and more.

```clojure
{"user-from-oracle-db" {:oracle/db :query-user :where [:= :session-id "SESSION-FSD123F1N1ASJ12UIVC"]}
 "inserting-info-about-user-in-mysql"
 {:mysql/db :add-some-user-action
  :insert [{:action "open-app" :user {:commando/from ["user-from-oracle-db"] :=> [:get :login]}}
		   {:action "query-insurense-data" :user {:commando/from ["user-from-oracle-db"] :=> [:get :login]}}
		   ...]}}
```

In the above example, Commando combines queries to two different databases, enabling you to compose effective scripts, migrations, DTO structures, etc.

```clojure
{"roles"
 {"admin-role"
  {:sql> "INSERT INTO permission-table(role,description) VALUES ((\"admin\", \"...\"))"
   :sql< "SELECT id FROM permission-table WHERE role = \"admin\" "}
  "service-role"
  {:sql> "INSERT INTO permission-table(role,description) VALUES ((\"service\", \"...\"))"
   :sql< "SELECT id FROM permission-table WHERE role = \"service\" "}
  "user-role"
  {:sql> "INSERT INTO permission-table(role,description) VALUES ((\"user\", \"...\"))"
   :sql< "SELECT id FROM permission-table WHERE role = \"user\" "}}
 "users"
 [{:sql/insert-into :user-table
   :record {:fname "Adam" :lname "West" :role {:commando/from ["roles" "admin-role"]}}}
  {:sql/insert-into :user-table
   :record {:fname "Bat" :lname "Man" :role {:commando/from ["roles" "admin-role"]}}}
  ...]}
```

The instruction above clearly explains the processes, and creates the required bindings which, when maintained, will help visualize and support your business logic.


As Commando is simply a graph-based resolver with easy configuration, it is not limited by any architectural constraints or specific framework.

## Basics

```clojure
(require '[commando.core :as commando])
(require '[commando.commands.builtin :as commands-builtin])

(commando/execute
  [commands-builtin/command-from-spec] ;; <--- CommandRegistry
  ;;
  ;;   .---- Instruction
  ;;  V
  {"1" 1
   ;;                                 Command -----.
   ;;                                              |
   "2" {:commando/from ["1"] :=> [:fn inc]} ;; <---'
   "3" {:commando/from ["2"] :=> [:fn inc]}})
;; => {:instruction {"1" 1, "2" 2, "3" 3}}
```

The above function composes "Instructions", "Commands", and a "CommandRegistry".
- **Instruction**: a Clojure map, large or small, containing data and _commands_. The instruction describes the data structure and the transformations to apply.
- **Command**: a data-lexeme that is evaluated and returns a result. The rules for parsing and executing commands are flexible and customizable. Command `:commando/from` returns a value by absolute or relative path, with optional post-processing via the `:=>` driver key.
- **CommandRegistry**: a vector of CommandMapSpecs describing data-lexemes that should be treated as _commands_ by the library. The order defines the command scan priority. You can also pre-build a registry with `registry-create` and pass it directly. Use `registry-add` / `registry-remove` to modify a built registry.

### Builtin Functionality

The basic commands is found in namespace `commando.commands.builtin`. It describes core commands and their behaviors. Behavior of those commands declared with configuration map called _CommandMapSpecs_.

#### command-from-spec

Retrieves a value from the instruction by path. Use the `:=>` driver key to post-process the result (see [Drivers](#drivers-post-processing)).

**Absolute path** — list of keys from the instruction root:

```clojure
(commando/execute
  [commands-builtin/command-from-spec]
  {:catalog {:price 99}
   :ref      {:commando/from [:catalog :price]}})
;; => {:catalog {:price 99}, :ref 99}
```

**Relative path** — `"../"` goes up one level from the command's position, `"./"` stays at the current level:

```clojure
(commando/execute
  [commands-builtin/command-from-spec]
  {"section-a" {"price" 10 "ref" {"commando-from" ["../" "price"]}}
   "section-b" {"price" 20 "ref" {"commando-from" ["../" "price"]}}})
;; => {"section-a" {"price" 10, "ref" 10}
;;     "section-b" {"price" 20, "ref" 20}}
```

**Named anchors** — mark any map with `"__anchor"` or `:__anchor`, then jump to it with `"@name"` regardless of nesting depth. The resolver finds the nearest ancestor with that name, so duplicate anchor names are safe — each command resolves to its own closest one:

```clojure
(commando/execute
  [commands-builtin/command-from-spec]
  {:items [{:__anchor "item" :price 10 :total {:commando/from ["@item" :price]}}
           {:__anchor "item" :price 20 :total {:commando/from ["@item" :price]}}]})
;; => {:items [{:__anchor "item", :price 10, :total 10}
;;             {:__anchor "item", :price 20, :total 20}]}
```

Anchors and `"../"` can be combined in one path — after jumping to the anchor, navigation continues from there:

```clojure
{:commando/from ["@section" "../" :base-price]}
```

#### command-fn-spec

A convenient wrapper over `apply`.

```clojure
(commando/execute
  [commands-builtin/command-fn-spec]
  {:commando/fn +
   :args [1, 2, 3]})
;; => 6

(commando/execute
  [commands-builtin/command-fn-spec]
  {"v1" 1
   "v2" 2
   "sum="
   {:commando/fn +
	:args [{:commando/from ["v1"]}
		   {:commando/from ["v2"]}
		   3]}})
;; => {"v1" 1 "v2" 2 "sum=" 6}
```

#### command-apply-spec

Returns the value of `:commando/apply` as-is. Use `:=>` driver to post-process the result.

```clojure
(commando/execute
  [commands-builtin/command-apply-spec]
  {"0" {:commando/apply
		{"1" {:commando/apply
			  {"2" {:commando/apply
					{"3" {:commando/apply {"4" {:final "5"}}
						  :=> [:get "4"]}}
					:=> [:get "3"]}}
			  :=> [:get "2"]}}
		:=> [:get "1"]}})
;; => {"0" {:final "5"}}
```

#### command-mutation-spec

Imagine the following instruction is your initial database migration, adding users to the DB:

```clojure
(commando/execute
  [commands-builtin/command-from-spec
   commands-builtin/command-mutation-spec]
  {"add-new-user-01" {:commando/mutation :add-user :name "Bob Anderson"
					  :permissions [{:commando/from ["perm_send_mail"] :=> [:get :id]}
					  {:commando/from ["perm_recv_mail"] :=> [:get :id]}]}
   "add-new-user-02" {:commando/mutation :add-user :name "Damian Nowak"
					  :permissions [{:commando/from ["perm_recv_mail"] :=> [:get :id]}]}
   "perm_recv_mail" {:commando/mutation :add-permission
					 :name "receive-email-notification"}
   "perm_send_mail" {:commando/mutation :add-permission
					 :name "send-email-notification"}})
```

You can see that you need both :add-permission and :add-user commands. In most cases, such patterns can be abstracted and reused, simplifying your migrations and business logic.

`commando-mutation-spec` uses `defmethod commando.commands.builtin/command-mutation` underneath, making it easy to wrap business logic into commands and integrate them into your instructions/migrations:

```clojure
(defmethod commands-builtin/command-mutation :add-user [_ {:keys [name permissions]}]
  ;; => INSERT INTO user VALUES (name, permissions)
  ;; => SELECT * FROM user WHERE name = name
  ;; =RETURN> {:id 1 :name "Bob Anderson"}
  )

(defmethod commands-builtin/command-mutation :add-permission [_ {:keys [name]}]
  ;; => INSERT INTO permission VALUES (name)
  ;; => SELECT * FROM permission WHERE name = name
  ;; =RETURN> {:id 1 :name name}
  )
```

This approach enables you to quickly encapsulate business logic into reusable commands, which can then be easily composed in your instructions or migrations.

#### command-macro-spec

Allows describing reusable command templates that are expanded into regular Commando commands at runtime. This is useful when you want to describe a pattern for building a complex command or a set of related commands without duplicating the same structure throughout an instruction

Asume we have a Instruction what calculates mean.
```clojure
(commando/execute
  [commands-builtin/command-from-spec
   commands-builtin/command-apply-spec
   commands-builtin/command-fn-spec]
  {:=> [:get :result]
   :commando/apply
   {:vector-of-numbers [1, 2, 3, 4, 5]
	:result
	{:fn (fn [& [vector-of-numbers]]
		   (/ (reduce + 0 vector-of-numbers)
			 (count vector-of-numbers)))
	 :args [{:commando/from [:commando/apply :vector-of-numbers]}]}}})
;; => 3
```

This works, but the structure is not very easy to read when repeated. When you need the same mean calculation many times, the instruction quickly grows and becomes hard to follow. A macro can help by encapsulating the pattern into a readable reusable shortcut.

Define a macro

```clojure
(defmethod commands-builtin/command-macro :mean-calc [{vector-of-numbers :vector-of-numbers}]
  {:=> [:get :result]
   :commando/apply
   {:vector-of-numbers vector-of-numbers
	:result
	{:fn (fn [& [vector-of-numbers]]
		   (/ (reduce + 0 vector-of-numbers)
			 (count vector-of-numbers)))
	 :args [{:commando/from [:commando/apply :vector-of-numbers]}]}}})


(commando/execute
  [commands-builtin/command-macro-spec
   commands-builtin/command-from-spec
   commands-builtin/command-apply-spec
   commands-builtin/command-fn-spec]
  {:v1 {:commando/macro :mean-calc :vector-of-numbers [1, 2, 3, 4, 5]}
   :v2 {:commando/macro :mean-calc :vector-of-numbers [10, 22, 33]}
   :v3 {:commando/macro :mean-calc :vector-of-numbers [7, 8, 1000, 1]}})
;; =>
;; {:v1 3
;;  :v2 21.666
;;  :v3 254}
```

command-macro-spec detects entries with `:commando/macro` and calls the multimethod `(defmethod) commands-builtin/command-macro` using the macro identifier (e.g. `:mean-calc`) and the parameter map from the instruction.

The defmethod should return a Instruction. Commando will then treat that returned map as a fully separate instruction: dependencies (like `:commando/from`) are discovered inside the macro hierarchy.

Use these macro handlers to hide repeated command structure and keep your instructions shorter and easier to read.

#### command-context-spec

Injects external reference data (dictionaries, config, feature flags) into instructions without duplicating it. Unlike other commands, `command-context-spec` is a **function** — call it with your context map to get a CommandMapSpec. The data is captured via closure and resolves before other commands (`{:mode :none}`), so `:commando/from` and `:commando/fn` can reference context results through the standard dependency mechanism.

```clojure
(def game-config
  {:heroes   {"warrior" {:hp 120 :damage 15}
              "mage"    {:hp 80  :damage 25}}
   :buffs    {:fire-sword 1.5 :shield 2.0}
   :settings {:difficulty "hard" :max-level 60}})

(commando/execute
  [(commands-builtin/command-context-spec game-config)
   commands-builtin/command-from-spec
   commands-builtin/command-fn-spec]
  {:warrior     {:commando/context [:heroes "warrior"]}
   :fire-bonus  {:commando/context [:buffs :fire-sword]}
   :hit-damage  {:commando/fn * :args [{:commando/from [:warrior] :=> [:get :damage]}
                                        {:commando/from [:fire-bonus]}]}
   :arena-name  {:commando/context [:arenas :default] :default "Colosseum"}})
;; => {:warrior {:hp 120 :damage 15}
;;     :fire-bonus 1.5
;;     :hit-damage 22.5
;;     :arena-name "Colosseum"}
```

Missing path returns `nil`; use `:default` for an explicit fallback. Use `:=>` driver for post-processing the resolved value, same as in `:commando/from`. String-key form (`"commando-context"`, `"default"`, `"=>"`) is available for JSON compatibility.

### Drivers (Post-Processing)

Drivers provide a **data-driven** way to post-process command results. After a command's `:apply` function produces a raw result, the driver transforms it before the value is placed back into the instruction.

A driver is declared via the `:=>` key (or `"=>"` for string-key/JSON maps) on any command, using a **vector DSL**:

```clojure
;; Vector form — [driver-name & params]
{:commando/.. :=> [:get :name]}
{:commando/.. :=> [:get-in [:address :city]]}
{:commando/.. :=> [:select-keys [:name :email]]}
{:commando/.. :=> [:fn inc]}
;; Keyword form — driver with no params
{:commando/.. :=> :my-custom-driver}
;; Pipeline — first element is a vector, steps are chained left-to-right
{:commando/.. :=> [[:get :address] [:get-in [:location :city]] :uppercase]}
```

If no `:=>` is specified, the default driver is `:get-in` with no params, which acts as identity (pass-through).

```clojure
(commando/execute
 [commands-builtin/command-from-spec]
 {:data {:name "John" :age 30 :email "john@example.com" :internal-id 999}
  :subset {:commando/from [:data]
           :=> [:select-keys [:name :email]]}})
;; => {:data {...}, :subset {:name "John", :email "john@example.com"}}
```

#### Built-in Drivers

**`:identity`** — pass-through behavior (enabled by default):

```clojure
{:commando/from [:data] :=> :identity}
;; {:name "John" :age 30}  => {:name "John" :age 30}
```

**`:get`** — extract a single key from the result:

```clojure
{:commando/from [:data] :=> [:get :name]}
;; {:name "John" :age 30}  =>  "John"
```

**`:get-in`** — extract a value at a deep path (also the default driver):

```clojure
{:commando/from [:data] :=> [:get-in [:address :location :city]]}
;; {:address {:location {:city "Kyiv"}}}  =>  "Kyiv"
```

**`:select-keys`** — select a subset of keys:

```clojure
{:commando/from [:data] :=> [:select-keys [:name :email]]}
;; {:name "John" :age 30 :email "j@e.com"}  =>  {:name "John" :email "j@e.com"}
```

**`:fn`** — apply an arbitrary function (for cases when you need runtime transforms):

```clojure
{:commando/from [:data] :=> [:fn inc]}
{:commando/from [:data] :=> [:fn #(reduce + %)]}
```

**`:projection`** — rename and reshape fields (pure data, no function transforms):

```clojure
{:commando/from [:data]
 :=> [:projection [[:user-id :id]
                   [:user-name [:profile :full-name]]
                   [:city [:address :location :city]]]]}
;; Each field spec: [output-key]
;;                   [output-key source-key-or-path]
```

Full projection example:

```clojure
(commando/execute
  [commands-builtin/command-from-spec]
  {:data {:id "u-101"
          :profile {:full-name "John Doe"}
          :address {:location {:city "kyiv"} :zip "01001"}
          :metadata {:labels ["important" "urgent"]}}
   :result {:commando/from [:data]
            :=> [:projection [[:user-id :id]
                              [:user-name [:profile :full-name]]
                              [:city [:address :location :city]]
                              [:tags [:metadata :labels]]]]}})
;; => {:data {...}
;;     :result {:user-id "u-101"
;;              :user-name "John Doe"
;;              :city "kyiv"
;;              :tags ["important" "urgent"]}}
```

#### Pipeline

When the first element of `:=>` is itself a vector, the entire value is treated as a **pipeline** — a sequence of driver steps chained left-to-right. Each step's output becomes the next step's input:

```clojure
(commando/execute
  [commands-builtin/command-from-spec]
  {:data {:profile {:name "john doe"} :age 30 :secret "x"}
   ;; Step 1: get :profile → {:name "john doe"}
   ;; Step 2: get :name    → "john doe"
   ;; Step 3: uppercase    → "JOHN DOE"
   :result {:commando/from [:data]
            :=> [[:get :profile] [:get :name] :uppercase]}})
;; => {:data {...}, :result "JOHN DOE"}
```

Each step in the pipeline can be:
- A **vector** `[:driver-name & params]` — e.g. `[:get :name]`, `[:get-in [:a :b]]`, `[:fn inc]`
- A **keyword** `:driver-name` — shorthand for a parameterless driver, e.g. `:uppercase`
- A **string** `"driver-name"` — for JSON compatibility, keywordized at runtime

Pipeline works with JSON string keys too:

```clojure
{"data" {"city" "kyiv"}
 "result" {"commando-from" ["data"] "=>" [["get" "city"] "uppercase"]}}
```

#### Custom Drivers

Drivers use Clojure's multimethod system. Define a new driver by extending `commando.impl.executing/command-driver`:

```clojure
(require '[commando.impl.executing :as commando-executing])

(defmethod commando-executing/command-driver :uppercase
  [_driver-name _driver-params applied-result _command-data _instruction _command-path-obj]
  (if (string? applied-result)
    (clojure.string/upper-case applied-result)
    applied-result))

;; Usage:
{:commando/from [:data] :=> :uppercase}
```

The driver function receives six arguments:
1. `driver-name` — keyword (dispatch value)
2. `driver-params` — seq of parameters from the `:=>` vector (`nil` for keyword-only drivers)
3. `applied-result` — the value returned by `:apply`
4. `command-data` — the original command map before `:apply` ran
5. `instruction` — the full instruction map
6. `command-path-obj` — `CommandMapPath` object

#### Drivers and JSON Compatibility

All built-in driver declarations (except `:fn`) are plain data — keywords, strings, and vectors — making them fully serializable to JSON, EDN, or Transit:

```clojure
;; JSON-compatible instruction with drivers
{"data" {"name" "John" "age" 30}
 "name" {"commando-from" ["data"]
         "=>" ["get" "name"]}}

;; Pipeline in JSON
{"data" {"address" {"city" "kyiv"}}
 "result" {"commando-from" ["data"]
           "=>" [["get-in" ["address" "city"]] "uppercase"]}}
```

String driver names are automatically keywordized at runtime.

### Adding new commands

As you start using commando, you will start writing your own command specs to match your data needs.

Here's an example of another instruction, utilizing step-by-step extraction of keys A and B from a structure:

```clojure
(commando/execute
  [commands-builtin/command-from-spec]
  {"1" {:values {:a 1 :b -1}}
   "a-value" {:commando/from ["1" :values :a] :=> [:fn (partial * 100)]}
   "b-value" {:commando/from ["1" :values :b] :=> [:fn (partial * -100)]}
   "args" {:a {:commando/from ["a-value"]}
		   :b {:commando/from ["b-value"]}}
   "summ=" {:commando/from ["args"] :=> [:fn (fn [{:keys [a b]}] (+ a b))]}})
;; =>
;; {"1" {:values {:a 1, :b -1}},
;;  "a-value" 100,
;;  "b-value" 100,
;;  "args" {:a 100, :b 100},
;;  "summ=" 200}
```

The main challenge with the above instruction is that everything is processed via the `:=> [:fn ...]` driver. This may be improved by introducing custom command types.

Let's create a new command using a CommandMapSpec configuration map:

```clojure
{:type :CALC=
 :recognize-fn #(and (map? %) (contains? % :CALC=))
 :validate-params-fn (fn [m]
					   (and
						 (fn? (:CALC= m))
						 (not-empty (:ARGS m))))
 :apply (fn [_instruction _command m]
			 (apply (:CALC= m) (:ARGS m)))
 :dependencies {:mode :all-inside}}
```

- `:type` - a unique identifier for this command.
- `:recognize-fn` - a predicate that recognizes that a structure `{:CALC= ...}` is a command, not just a generic map.
- `:validate-params-fn` (optional) - validates the structure after recognition.
- `:apply` - the function that directly executes the command as params it receives whole instruction, command spec and as a last argument what was recognized by :cm/recognize
- `:dependencies` - describes the type of dependency this command has. Commando supports three modes:
  - `{:mode :all-inside}` - the command scans itself for dependencies on other commands within its body.
  - `{:mode :none}` - the command has no dependencies and can be evaluated whenever.
  - `{:mode :point :point-key [:commando/from]}` - allowing to be dependent anywhere in the instructions. Expects point-key(-s) which tells where is the dependency (commando/from as an example uses this)

Now you can use it for more expressive operations like "summ=" and "multiply=" as shown below:

```clojure
;; Build a registry — vector order defines scan priority
(def command-registry
  (commando/registry-create
	[commands-builtin/command-from-spec
	 {:type :CALC=
	  :recognize-fn #(and (map? %) (contains? % :CALC=))
	  :validate-params-fn (fn [m]
							(and
							  (fn? (:CALC= m))
							  (not-empty (:ARGS m))))
	  :apply (fn [_instruction _command m]
				(apply (:CALC= m) (:ARGS m)))
	  :dependencies {:mode :all-inside}}]))

;; Modify a built registry
(def extended-registry
  (commando/registry-add command-registry
    {:type :NEW-CMD
     :recognize-fn #(and (map? %) (contains? % :NEW-CMD))
     :apply (fn [_ _ m] (:NEW-CMD m))
     :dependencies {:mode :none}}))

(def shrunk-registry
  (commando/registry-remove extended-registry :NEW-CMD))

(commando/execute
  command-registry
  {"1" {:values {:a 1 :b -1}}
   "a-value" {:commando/from ["1" :values :a] :=> [:fn (partial * 100)]}
   "b-value" {:commando/from ["1" :values :b] :=> [:fn (partial * -100)]}
   "summ=" {:CALC= +
			:ARGS [{:commando/from ["a-value"]}
				   {:commando/from ["b-value"]}
				   1
				   11]}
   "multiply=" {:CALC= *
				:ARGS [{:commando/from ["a-value"]}
					   {:commando/from ["b-value"]}
					   2
					   22]}})
;; =>
;; {"1" {:values {:a 1, :b -1}},
;;  "a-value" 100,
;;  "b-value" 100,
;;  "summ=" 212,
;;  "multiply=" 440000}
```

The concept of a **command** is not limited to map structures it is basically anything that you can express with recognize predicate. For example, you can define a command that recognizes and parses JSON strings:

```clojure
(commando/execute
  [{:type :custom/json
	:recognize-fn #(and (string? %) (clojure.string/starts-with? % "json"))
	:apply (fn [_instruction _command-map string-value]
			  (clojure.data.json/read-str (apply str (drop 4 string-value))
				:key-fn keyword))
	:dependencies {:mode :none}}]
  {:json-command-1 "json{\"some-json-value-1\": 123}"
   :json-command-2 "json{\"some-json-value-2\": [1, 2, 3]}"})
;; =>
;; {:json-command-1 {:some-json-value-1 123},
;;  :json-command-2 {:some-json-value-2 [1 2 3]}}
```


## Status-Map and Internals

The main function for executing instructions is `commando.core/execute`, which returns a so-called Status-Map. A Status-Map is a data structure that contains the outcome of instruction execution, including results, successes, warnings, errors, and internal execution order.)

On successful execution (`:ok`), you get:
- `:instruction` - the resulting evaluated data map.
- `:successes` - information about successful execution steps.

```clojure
(require '[commando.core :as commando])
(require '[commando.commands.builtin :as commands-builtin])

(commando/execute
  [commands-builtin/command-from-spec]
  {"1" 1
   "2" {:commando/from ["1"]}
   "3" {:commando/from ["2"]}})

;; RETURN =>
{:status :ok,
 :instruction {"1" 1, "2" 1, "3" 1}
 :stats
 [["execute-commands!" 95838   "95.838µs"]
  ["execute"           1085471 "1.085471ms"]]
 :successes
 [{:message
   "Commando. parse-instruction-map. Entities was successfully collected"}
  {:message
   "Commando. build-deps-tree. Dependency map was successfully built"}
  {:message
   "Commando. sort-entities-by-deps. Entities were sorted and prepared for evaluation"}
  {:message
   "Commando. compress-execution-data. Status map was compressed"}
  {:message
   "Commando. evaluate-instruction-commands. Data term was processed"}]}
```

On unsuccessful execution (`:failed`), you get:
- `:instruction` - the partially or completely unexecuted instruction given by the user
- `:successes` - a list of successful actions completed before the failure
- `:warnings` - a list of non-critical errors or skipped steps
- `:errors` - a list of error objects, sometimes with exception data or additional keys
- `:internal/cm-list` (optional) - a list of Command objects with command meta-information
- `:internal/cm-dependency` (optional) - a map of dependencies
- `:internal/cm-running-order` (optional) - the resulting list of commands to be executed in order

```clojure
(require '[commando.core :as commando])
(require '[commando.commands.builtin :as commands-builtin])

(commando/execute
  [commands-builtin/command-from-spec]
  {"1" 1
   "2" {:commando/from ["1"]}
   "3" {:commando/from ["WRONG" "PATH"]}})

;; RETURN =>
{:status :failed
 :instruction
 {"1" 1
  "2" {:commando/from ["1"]}
  "3" {:commando/from ["WRONG" "PATH"]}}
 :errors
 [{:message "build-deps-tree. Failed to build `:point` dependency. Key `Commando.` with path: `:commando/from`, - referring to non-existing value",
   :path ["3"],
   :command {:commando/from ["WRONG" "PATH"]}}],
 :warnings
 [{:message
   "Commando. sort-entities-by-deps. Skipping mandatory step"}
  {:message
   "Commando. compress-execution-data. Skipping mandatory step"}
  {:message
   "Commando. evaluate-instruction-commands. Skipping mandatory step"}],
 :successes
 [{:message
   "Commando. parse-instruction-map. Entities were successfully collected"}],
 :internal/cm-list
 [#<CommandMapPath "root[_map]">
  #<CommandMapPath "root,3[from]">
  #<CommandMapPath "root,2[from]">
  #<CommandMapPath "root,1[_value]">]}
```

### Configuring Execution Behavior

The `commando.impl.utils/*execute-config*` dynamic variable allows for fine-grained control over `commando/execute`'s behavior. You can bind this variable to a map containing the following configuration keys:

- `:debug-result` (boolean)
- `:error-data-string` (boolean)

#### `:debug-result`

When set to `true`, the returned status-map will include additional execution information, such as `:internal/cm-list`, `:internal/cm-dependency`, and `:internal/cm-running-order`. This helps in analyzing the instruction's execution flow.

Here's an example of how to use `:debug-result`:

```clojure
(require '[commando.core :as commando])
(require '[commando.commands.builtin :as commands-builtin])
(require '[commando.impl.utils :as commando-utils])

(binding [commando-utils/*execute-config* {:debug-result true}]
  (commando/execute
	[commands-builtin/command-from-spec]
	{"1" 1
	 "2" {:commando/from ["1"]}
	 "3" {:commando/from ["2"]}}))

;; RETURN =>
{:status :ok,
 :instruction {"1" 1, "2" 1, "3" 1}
 :stats
 [["use-registry"           111876 "111.876µs"]
  ["find-commands"          303062 "303.062µs"]
  ["build-deps-tree"        134049 "134.049µs"]
  ["sort-commands-by-deps"  292206 "292.206µs"]
  ["execute-commands!"       53762 "53.762µs"]
  ["execute"               1074110 "1.07411ms"]]
 :registry
 [{:type               :commando/from,
   :recognize-fn       #function[commando.commands.builtin/fn],
   :validate-params-fn #function[commando.commands.builtin/fn],
   :apply              #function[commando.commands.builtin/fn],
   :dependencies       {:mode :point, :point-key [:commando/from]}}],
 :warnings [],
 :errors [],
 :successes
 [{:message "Commands were successfully collected"}
  {:message "Dependency map was successfully built"}
  {:message "Commando. sort-entities-by-deps. Entities was sorted and prepare for evaluating"}
  {:message "All commands executed successfully"}],
 :internal/cm-list
 ["root[_map]"
  "root,1[_value]"
  "root,2[from]"
  "root,3[from]"]
 :internal/cm-running-order
 ["root,2[from]"
  "root,3[from]"],
 :internal/cm-dependency
 {"root[_map]"     #{"root,2[from]" "root,1[_value]" "root,3[from]"},
  "root,1[_value]" #{},
  "root,2[from]"   #{"root,1[_value]"},
  "root,3[from]"   #{"root,2[from]"}}}
```

`:internal/cm-list` - a list of all recognized commands in an instruction. This list also contains the `_map`, `_value`, and the unmentioned `_vector` commands. Commando includes several internal built-in commands that describe the _instruction's structure_. An _instruction_ is a composition of maps, their values, and vectors that represent its structure and help build a clear dependency graph. These commands are removed from the final output after this step, but included in the compiled registry.

`:internal/cm-dependency` - describes how parts of an _instruction_ depend on each other.

`:internal/cm-running-order` - the correct order in which to execute commands.


#### `:error-data-string`

When `:error-data-string` is `true`, the `:data` key within serialized `ExceptionInfo` objects (processed by `commando.impl.utils/serialize-exception`) will contain a string representation of the exception's data. Conversely, if `false`, the `:data` key will hold the raw data structure (map). This setting is particularly useful for controlling the verbosity of error details, in example when examining Malli validation explanations etc.

```clojure
(def value
  (commando/execute [commands-builtin/command-from-spec]
    {"a" 10
     "ref" {:commando/from "BROKEN"}}))
(get-in value [:errors 0 :error])
;; =>
;; {:type "exception-info",
;;  :class "clojure.lang.ExceptionInfo",
;;  :message "Failed while validating params for :commando/from ...",
;;  :stack-trace
;;  [["commando.impl.finding_commands$instruction_command_spec$fn__14401" "invoke" "finding_commands.cljc" 65]
;;   ["clojure.core$some" "invokeStatic" "core.clj" 2718]
;;   ...
;;   ...],
;;  :cause nil,
;;  :data "{:command-type :commando/from, :reason #:commando{:from [\"commando/from should be a sequence path to value in Instruction: [:some 2 \\\"value\\\"]\"]}, :path [\"ref\"], :value #:commando{:from \"BROKEN\"}}"}


(def value
  (binding [sut/*execute-config* {:error-data-string false}]
    (commando/execute [commands-builtin/command-from-spec]
      {"a" 10
       "ref" {:commando/from "BROKEN"}})))
(get-in value [:errors 0 :error])
;; =>
;; {:type "exception-info",
;;  :class "clojure.lang.ExceptionInfo",
;;  ...
;;  ...
;;  :data
;;  {:command-type :commando/from,
;;   :reason {:commando/from
;;            ["commando/from should be a sequence path to value in Instruction: [:some 2 \"value\"]"]},
;;   :path ["ref"],
;;   :value {:commando/from "BROKEN"}}}
```


### Performance

Commando is designed for high performance, using efficient algorithms for dependency resolution and command execution to process complex instructions swiftly.

All benchmarks were conducted on an **Intel Core i9-13980HX**. The primary metric for performance is the number of dependencies within an instruction.

#### Total Execution Time (Typical Workloads)

The graph below illustrates the total execution time for instructions with a typical number of dependencies, ranging from 1,250 to 80,000. As you can see, the execution time scales linearly and remains in the low millisecond range, demonstrating excellent performance for common use cases.

<div align="center">
<img width="100%" src="./test/perf/commando/execute(normal) milisecs_x_deps.png">
</div>

#### Execution Step Analysis

To provide deeper insight, we've broken down the execution into five distinct steps:
1.  **use-registry**: Builds the command registry from the provided specs.
2.  **find-commands**: Scans the instruction map to identify all command instances.
3.  **build-deps-tree**: Constructs a Directed Acyclic Graph (DAG) of dependencies between commands.
4.  **sort-commands-by-deps**: Sorts the commands based on the dependency graph to determine the correct execution order.
5.  **execute-commands!**: Executes the commands in the resolved order.

The following graphs show the performance of each step under both normal and extreme load conditions.

**Normal Workloads (up to 80,000 dependencies)**

Under normal conditions, each execution step completes in just a few milliseconds. The overhead of parsing, dependency resolution, and execution is minimal, ensuring a fast and responsive system.

<div align="center">
<img width="100%" src="./test/perf/commando/execute-steps(normal) milisecs_x_deps.png">
</div>

**Massive Workloads (up to 5,000,000 dependencies)**

To test the limits of the library, we benchmarked it with instructions containing up to 5 million dependencies. The graph below shows that while the system scales, the `find-commands` (parsing) and `build-deps-tree` (dependency graph construction) phases become the primary bottlenecks. This demonstrates that the core execution remains fast, but performance at extreme scales is dominated by the initial analysis steps.

<div align="center">
<img width="100%" src="./test/perf/commando/execute-steps(massive dep grow) secs_x_deps.png">
</div>

# Integrations

- [Work with JSON](./doc/json.md)
- [Frontend + Reagent](./doc/reagent.md)
- [Commando + integrant](./doc/integrant.md)
- [Commando QueryDSL](./doc/query_dsl.md)
- [Example Http commando transit handler + Reitit](./doc/example_reitit.clj)

# Versioning

We comply with: [Break Versioning](https://www.taoensso.com/break-versioning)
`<major>.<minor>.<non-breaking>[-<optional-qualifier>]`

For version changes look to CHANGELOG

# License

This project is licensed under the Eclipse Public License (EPL).
