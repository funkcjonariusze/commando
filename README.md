<div align="center">
<img width="30%" src="./logo/Commando.png">
</div>

[![Clojars Project](https://img.shields.io/clojars/v/org.clojars.funkcjonariusze/commando.svg)](https://clojars.org/org.clojars.funkcjonariusze/commando)
[![Run tests](https://github.com/funkcjonariusze/commando/actions/workflows/unit_test.yml/badge.svg)](https://github.com/funkcjonariusze/commando/actions/workflows/unit_test.yml)
[![cljdoc badge](https://cljdoc.org/badge/org.clojars.funkcjonariusze/commando)](https://cljdoc.org/d/org.clojars.funkcjonariusze/commando/1.0.1)

**Commando** is a flexible Clojure library for managing, extracting, and transforming data inside nested map structures using a declarative command-based DSL.

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
  - [Adding New Commands](#adding-new-commands)
- [Status-Map and Internals](#status-map-and-internals)
  - [Debugging commando](#debugging-commando)
- [Integrations](#integrations)
- [Versioning](#versioning)
- [License](#license)

## Installation

```clojure
;; deps.edn with git
{org.clojars.funkcjonariusze/commando {:mvn/version "1.0.0"}}

;; leiningen
[org.clojars.funkcjonariusze/commando "1.0.0"]
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
  :insert [{:action "open-app" :user {:commando/from ["user-from-oracle-db"] := :login}}
		   {:action "query-insurense-data" :user {:commando/from ["user-from-oracle-db"] := :login}}
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
   ;;                        Command -----.
   ;;                                     |
   "2" {:commando/from ["1"] := inc} ;; <-'
   "3" {:commando/from ["2"] := inc}})
;; => {:instruction {"1" 1, "2" 2, "3" 3}}
```

The above function composes "Instructions", "Commands", and a "CommandRegistry".
- **Instruction**: a Clojure map, large or small, containing data and _commands_. The instruction describes the data structure and the transformations to apply.
- **Command**: a data-lexeme that is evaluated and returns a result. The rules for parsing and executing commands are flexible and customizable. Command `:command/from` return value by the absolute or relative path, can optionally apply a function provided under the `:=` key.
- **CommandRegistry**: a vector describing data-lexemes that should be treated as _commands_ by the library.

### Builtin Functionality

The basic commands is found in namespace `commando.commands.builtin`. It describes core commands and their behaviors. Behavior of those commands declared with configuration map called _CommandMapSpecs_.

#### command-from-spec

Allows retrieving data from the instruction by referencing it via the `:commando/from` key. An optional function can be applied via the `:=` key.

The `:commando/from` command supports relative paths like `"../"`, `"./"` for accessing data. The example below shows how values "1", "2", and "3" can be incremented and decremented in separate map namespaces:

```clojure
(commando/execute
  [commands-builtin/command-from-spec]
  {"incrementing 1"
   {"1" 1
	"2" {:commando/from ["../" "1"] := inc}
	"3" {:commando/from ["../" "2"] := inc}}
   "decrementing 1"
   {"1" 1
	"2" {:commando/from ["../" "1"] := dec}
	"3" {:commando/from ["../" "2"] := dec}}})
;; =>
;;  {"incrementing 1" {"1" 1, "2" 2, "3" 3},
;;   "decrementing 1" {"1" 1, "2" 0, "3" -1}}
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

A wrapper similar to `commando/fn`, but conceptually closer to `commando/from`, operating on values already passed to the key.

```clojure
(commando/execute
  [commands-builtin/command-apply-spec]
  {"0" {:commando/apply
	{"1" {:commando/apply
	      {"2" {:commando/apply
		    {"3" {:commando/apply {"4" {:final "5"}}
			  := #(get % "4")}}
		    := #(get % "3")}}
	      := #(get % "2")}}
	:= #(get % "1")}})
;; => {"0" {:final "5"}}
```

#### command-mutation-spec

Imagine the following instruction is your initial database migration, adding users to the DB:

```clojure
(commando/execute
  [commands-builtin/command-from-spec
   commands-builtin/command-mutation-spec]
  {"add-new-user-01" {:commando/mutation :add-user :name "Bob Anderson"
		              :permissions [{:commando/from ["perm_send_mail"] := :id}
                      {:commando/from ["perm_recv_mail"] := :id }]}
   "add-new-user-02" {:commando/mutation :add-user :name "Damian Nowak"
                      :permissions [{:commando/from ["perm_recv_mail"] := :id}]}
   "perm_recv_mail" {:commando/mutation :add-permission
                     :name "receive-email-notification"}
   "perm_send_mail" {:commando/mutation :add-permission
	                 :name "send-email-notification"}})
```

You can see that you need both :add-permission and :add-user commands. In most cases, such patterns can be abstracted and reused, simplifying your migrations and business logic.

`commando-mutation-spec` uses `defmethod commando/command-mutation` underneath, making it easy to wrap business logic into commands and integrate them into your instructions/migrations:

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


### Adding new commands 

As you start using commando, you will start writing your own command specs to match your data needs.

Here's an example of another instruction, utilizing step-by-step extraction of keys A and B from a structure:

```clojure
(commando/execute
  [commands-builtin/command-from-spec]
  {"1" {:values {:a 1 :b -1}}
   "a-value" {:commando/from ["1" :values :a] := (partial * 100)}
   "b-value" {:commando/from ["1" :values :b] := (partial * -100)}
   "args" {:a {:commando/from ["a-value"]}
		   :b {:commando/from ["b-value"]}}
   "summ=" {:commando/from ["args"] := (fn [{:keys [a b]}] (+ a b))}})
;; =>
;; {"1" {:values {:a 1, :b -1}},
;;  "a-value" 100,
;;  "b-value" 100,
;;  "args" {:a 100, :b 100},
;;  "summ=" 200}
```

The main challenge with the above instruction is that everything is processed via the optional `:=` key in the `:commando/from` command. This may be improved by introducing custom command types.

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
- `:recognize` - a predicate that recognizes that a structure `{:CALC= ...}` is a command, not just a generic map.
- `:validate-params` (optional) - validates the structure after recognition.
- `:apply` - the function that directly executes the command as params it receives whole instruction, command spec and as a last argument what was recognized by :cm/recognize
- `:dependencies` - describes the type of dependency this command has. Commando supports three modes:
  - `{:mode :all-inside}` - the command scans itself for dependencies on other commands within its body.
  - `{:mode :none}` - the command has no dependencies and can be evaluated whenever. 
  - `{:mode :point :point-key :commando/from}` - allowing to be dependent anywhere in the instructions. Expects point-key which tells where is the dependency (commando/from as an example uses this)

Now you can use it for more expressive operations like "summ=" and "multiply=" as shown below:

```clojure
(def command-registry
  (commando/create-registry
    [;; Add `:commando/from`
     commands-builtin/command-from-spec
     ;; Add `:CALC=` command to be handled
     ;; inside instruction
     {:type :CALC=
      :recognize-fn #(and (map? %) (contains? % :CALC=))
      :validate-params-fn (fn [m]
                            (and
                              (fn? (:CALC= m))
                              (not-empty (:ARGS m))))
      :apply (fn [_instruction _command m]
                (apply (:CALC= m) (:ARGS m)))
      :dependencies {:mode :all-inside}}]))

(commando/execute
  command-registry
  {"1" {:values {:a 1 :b -1}}
   "a-value" {:commando/from ["1" :values :a] := (partial * 100)}
   "b-value" {:commando/from ["1" :values :b] := (partial * -100)}
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
	:recognize #(and (string? %) (clojure.string/starts-with? % "json"))
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

### Debugging commando

You can set dynamic variable `commando.impl.utils/*debug-mode* true` to see more details on how execution went.

```clojure
(require '[commando.core :as commando])
(require '[commando.commands.builtin :as commands-builtin])
(require '[commando.impl.utils :as commando-utils])

(binding [commando-utils/*debug-mode* true]
  (execute
    [commands-builtin/command-from-spec]
    {"1" 1
     "2" {:commando/from ["1"]}
     "3" {:commando/from ["2"]}}))
	 
;; RETURN => 
{:status :ok,
 :instruction {"1" 1, "2" 1, "3" 1}
 :registry
 [{:type               :commando/from,
   :recognize-fn       #function[commando.commands.builtin/fn],
   :validate-params-fn #function[commando.commands.builtin/fn],
   :apply              #function[commando.commands.builtin/fn],
   :dependencies       {:mode :point, :point-key :commando/from}}],
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

`:internal/cm-list` - a list of all recognized commands in an instruction. This list also contains the `_map`, `_value`, and the unmentioned `_vector` commands, which are not included in the registry. Commando includes several internal built-in commands that describe the _instruction's structure_. An _instruction_ is a composition of maps, their values, and vectors that represent its structure and help build a clear dependency graph. These commands are removed from the final output after this step.

`:internal/cm-dependency` - describes how parts of an _instruction_ depend on each other.

`:internal/cm-running-order` - the correct order in which to execute commands.

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
