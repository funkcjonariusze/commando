;; ┌─────────────────────────────────────────────────────┐
;; │ Commando Walkthrough                                │
;; └─────────────────────────────────────────────────────┘
;;
;; Evaluate each form in your REPL to see how it works.
;; By the end you will know every core building block.


;; ┌─────────────────────────────────────────────────────┐
;; │ 1. GETTING STARTED                                  │
;; └─────────────────────────────────────────────────────┘
;;
;; Two namespaces are all you need:
;; • `commando.core`             — the execution engine
;; • `commando.commands.builtin` — ready-to-use commands

(require '[commando.core :as commando])
(require '[commando.commands.builtin :as builtin])


;; ┌─────────────────────────────────────────────────────┐
;; │ 2. INSTRUCTIONS AND COMMANDS                        │
;; └─────────────────────────────────────────────────────┘
;;
;; An "instruction" is a Clojure map where:
;; • Some values are "commands" — patterns that Commando
;;   recognizes and evaluates.
;; • Everything else is plain data, left untouched.
;;
;; The first argument to `execute` is a "registry" — a vector
;; of command specs that tells Commando which patterns to
;; look for. The second argument is the instruction itself.

(commando/execute
  [builtin/command-from-spec]
  {:greeting "hello"
   :copy     {:commando/from [:greeting]}})
;; => {:status :ok,
;;     :instruction {:greeting "hello", :copy "hello"}, ...}
;;
;;   NOTE: Here we pass only `command-from-spec`, so only
;;         `:commando/from` is recognized as a command.

;; Commands can depend on other commands. Commando builds a
;; dependency graph and executes them in the right order —
;; you don't have to think about it.

(commando/execute
  [builtin/command-from-spec]
  {:a 1
   :b {:commando/from [:a]}
   :c {:commando/from [:b]}
   :d {:commando/from [:c]}})
;; => {:instruction {:a 1, :b 1, :c 1, :d 1}, ...}
;;
;; :d → :c → :b → :a — resolved automatically.

;; ─────────────────────────────────────────────────────
;;  Checking the result
;; ─────────────────────────────────────────────────────
;;
;; `execute` returns a status-map. Use `ok?` / `failed?`:

(let [result (commando/execute
               [builtin/command-from-spec]
               {:a 1 :b {:commando/from [:a]}})]
  (commando/ok? result))
;; => true
;;
;; To get just the evaluated instruction:

(let [result (commando/execute
               [builtin/command-from-spec]
               {:a 1 :b {:commando/from [:a]}})]
  (:instruction result))
;; => {:a 1, :b 1}


;; ┌─────────────────────────────────────────────────────┐
;; │ 3. BUILTIN COMMANDS                                 │
;; └─────────────────────────────────────────────────────┘
;;
;; Commando ships with six commands. Each one is a
;; CommandMapSpec — a config map that describes how to
;; recognize, validate, and execute a command type.

;; ─────────────────────────────────────────────────────
;;  :commando/from
;; ─────────────────────────────────────────────────────
;;
;; Retrieves a value from the instruction by path.

(:instruction
 (commando/execute
   [builtin/command-from-spec]
   {:catalog {:price 99}
    :ref     {:commando/from [:catalog :price]}}))
;; => {:catalog {:price 99}, :ref 99}

;; ─────────────────────────────────────────────────────
;;  :commando/from — relative paths
;; ─────────────────────────────────────────────────────
;;
;; `"../"` goes up one level from the command's position.
;; Each command resolves relative to where it sits.

(:instruction
 (commando/execute
   [builtin/command-from-spec]
   {"section-a" {"price" 10
                 "ref"   {"commando-from" ["../" "price"]}}
    "section-b" {"price" 20
                 "ref"   {"commando-from" ["../" "price"]}}}))
;; => {"section-a" {"price" 10, "ref" 10},
;;     "section-b" {"price" 20, "ref" 20}}

;; ─────────────────────────────────────────────────────
;;  :commando/from — named anchors
;; ─────────────────────────────────────────────────────
;;
;; Mark any map with `:__anchor`, then jump to it with
;; `"@name"` regardless of nesting depth. Each command
;; resolves to its nearest matching anchor.

(:instruction
 (commando/execute
   [builtin/command-from-spec]
   {:items [{:__anchor "item" :price 10 :total {:commando/from ["@item" :price]}}
            {:__anchor "item" :price 20 :total {:commando/from ["@item" :price]}}]}))
;; => {:items [{:__anchor "item", :price 10, :total 10}
;;             {:__anchor "item", :price 20, :total 20}]}

;; ─────────────────────────────────────────────────────
;;  :commando/fn
;; ─────────────────────────────────────────────────────
;;
;; Calls a function with arguments. Arguments can be plain
;; values or other commands (like `:commando/from`).

(:instruction
 (commando/execute
   [builtin/command-fn-spec
    builtin/command-from-spec]
   {:x 3
    :y 4
    :sum {:commando/fn + :args [{:commando/from [:x]}
                                {:commando/from [:y]}]}}))
;; => {:x 3, :y 4, :sum 7}

;; ─────────────────────────────────────────────────────
;;  :commando/apply
;; ─────────────────────────────────────────────────────
;;
;; Returns its value as-is — useful as a scoping container.
;; Combine with `:=>` to extract from the result.
;;
;;   NOTE: `:=>`/`"=>"` is a driver operator which allows
;;         accessing/transforming the result of an executed command.
;;         Explained in part 4.

(:instruction
 (commando/execute
   [builtin/command-apply-spec]
   {:result {:commando/apply {:nested {:value 42}}
             :=> [:get-in [:nested :value]]}}))
;; => {:result 42}

;; ─────────────────────────────────────────────────────
;;  :commando/context
;; ─────────────────────────────────────────────────────
;;
;; Injects external data (config, dictionaries) into an
;; instruction without duplicating it.
;;
;;   NOTE: `command-context-spec` is a function — call it
;;         with your context map to get a CommandMapSpec.

(def app-config
  {:db-host "localhost"
   :features {:dark-mode true}})

(:instruction
 (commando/execute
   [(builtin/command-context-spec app-config)
    builtin/command-from-spec]
   {:host      {:commando/context [:db-host]}
    :dark?     {:commando/context [:features :dark-mode]}
    :missing   {:commando/context [:nope] :default "fallback"}
    :host-copy {:commando/from [:host]}}))
;; => {:host "localhost", :dark? true,
;;     :missing "fallback", :host-copy "localhost"}

;; ─────────────────────────────────────────────────────
;;  :commando/mutation
;; ─────────────────────────────────────────────────────
;;
;; For side effects — database inserts, API calls, etc.
;; Define handlers via `defmethod` on `builtin/command-mutation`.

;; Example 1 — :save-user
;;
;; A mutation that simulates saving a user to a database.
;; The result feeds into a dependent command.

(defmethod builtin/command-mutation :save-user [_ {:keys [name email]}]
  (println "Saving user to database...")
  (Thread/sleep 300)
  {:id (random-uuid) :name name :email email :status :created})

(:instruction
 (commando/execute
   [builtin/command-mutation-spec
    builtin/command-from-spec]
   {:user    {:commando/mutation :save-user
              :name "Alice" :email "alice@example.com"}
    :status  {:commando/from [:user] :=> [:get :status]}}))
;; REPL output => Saving user to database...
;; => {:user {:id #uuid "...", :name "Alice", :email "alice@example.com", :status :created},
;;     :status :created}

;; Example 2 — :send-email (chained mutations)
;;
;; A second mutation that depends on the first one's result.
;; Commando resolves the dependency automatically — :send-email
;; waits for :save-user to finish.

(defmethod builtin/command-mutation :send-email [_ {:keys [to subject]}]
  (println (str "Sending email to " to "..."))
  (Thread/sleep 500)
  {:sent true :to to :subject subject})

(:instruction
 (commando/execute
   [builtin/command-mutation-spec
    builtin/command-from-spec]
   {:user  {:commando/mutation :save-user
            :name "Alice" :email "alice@example.com"}
    :email {:commando/mutation :send-email
            :to      {:commando/from [:user] :=> [:get :email]}
            :subject "Welcome aboard!"}}))
;; REPL output:
;;   Saving user to database...        ← runs first (dependency)
;;   Sending email to alice@example.com...
;; => {:user  {:id #uuid "...", :name "Alice", ...},
;;     :email {:sent true, :to "alice@example.com", :subject "Welcome aboard!"}}

;; ─────────────────────────────────────────────────────
;;  :commando/macro
;; ─────────────────────────────────────────────────────
;;
;; Macros let you define reusable instruction templates.
;; A macro expands into a sub-instruction that Commando
;; evaluates recursively.

(defmethod builtin/command-macro :double [_ params]
  {:commando/apply (* 2 (:value params))})

(:instruction
 (commando/execute
   [builtin/command-macro-spec
    builtin/command-apply-spec]
   {:a {:commando/macro :double :value 5}
    :b {:commando/macro :double :value 21}}))
;; => {:a 10, :b 42}


;; ┌─────────────────────────────────────────────────────┐
;; │ 4. DRIVERS (POST-PROCESSING)                        │
;; └─────────────────────────────────────────────────────┘
;;
;; After a command produces a result, a "driver" can
;; transform it. Drivers are declared with the `:=>` key
;; on any command.
;;
;;   TIP: Without `:=>`, the result passes through unchanged
;;        (identity driver).

;; ─────────────────────────────────────────────────────
;;  Built-in drivers
;; ─────────────────────────────────────────────────────
;;
;; • `:identity`    — pass-through value (default)
;; • `:get`         — extract a single key
;; • `:get-in`      — extract a value at a deep path
;; • `:select-keys` — pick a subset of keys
;; • `:fn`          — apply an arbitrary function
;; • `:projection`  — rename and reshape fields (pure data)

(:instruction
 (commando/execute
   [builtin/command-from-spec]
   {:user {:name "Alice" :age 30}
    :name {:commando/from [:user] :=> [:get :name]}}))
;; => {:user {:name "Alice", :age 30}, :name "Alice"}

(:instruction
 (commando/execute
   [builtin/command-from-spec]
   {:user {:name "Alice" :age 30 :password "secret"}
    :safe {:commando/from [:user] :=> [:select-keys [:name :age]]}}))
;; => {:user {...}, :safe {:name "Alice", :age 30}}

(:instruction
 (commando/execute
   [builtin/command-from-spec]
   {:price 100
    :with-tax {:commando/from [:price] :=> [:fn #(* % 1.2)]}}))
;; => {:price 100, :with-tax 120.0}

;; ─────────────────────────────────────────────────────
;;  Pipelines
;; ─────────────────────────────────────────────────────
;;
;; When the first element of `:=>` is a vector, it becomes
;; a pipeline. Steps are chained left-to-right — each
;; step's output feeds into the next:

(:instruction
 (commando/execute
   [builtin/command-from-spec]
   {:data {:profile {:name "alice"}}
    :result {:commando/from [:data]
             :=> [[:get :profile] [:get :name] [:fn clojure.string/upper-case]]}}))
;; => {:data {...}, :result "ALICE"}


;; ┌─────────────────────────────────────────────────────┐
;; │ 5. CUSTOM COMMANDS                                  │
;; └─────────────────────────────────────────────────────┘
;;
;; You can define entirely new command types with a
;; CommandMapSpec — a map with four keys:
;; • `:type`         — unique keyword identifier
;; • `:recognize-fn` — predicate to identify the command
;; • `:apply`        — execution function
;; • `:dependencies` — dependency mode

(:instruction
 (commando/execute
   [{:type        :shout
     :recognize-fn #(and (map? %) (contains? % :shout))
     :apply        (fn [_instruction _cmd-spec m]
                     (clojure.string/upper-case (:shout m)))
     :dependencies {:mode :none}}]
   {:a {:shout "hello world"}
    :b {:shout "commando is neat"}}))
;; => {:a "HELLO WORLD", :b "COMMANDO IS NEAT"}

;; ─────────────────────────────────────────────────────
;;  Non-map commands
;; ─────────────────────────────────────────────────────
;;
;; Commands are not limited to maps — anything your
;; `:recognize-fn` can match works. Here's a command
;; that recognizes strings ending with "!":

(:instruction
 (commando/execute
   [{:type         :bang
     :recognize-fn #(and (string? %) (clojure.string/ends-with? % "!"))
     :apply        (fn [_ _ s] (clojure.string/upper-case s))
     :dependencies {:mode :none}}]
   {:calm "hello" :excited "hello!"}))
;; => {:calm "hello", :excited "HELLO!"}


;; ┌─────────────────────────────────────────────────────┐
;; │ 6. JSON COMPATIBILITY                               │
;; └─────────────────────────────────────────────────────┘
;;
;; All built-in commands work with string keys for JSON
;; interop. Use `"commando-from"`, `"commando-fn"`,
;; `"=>"` etc.

(:instruction
 (commando/execute
   [builtin/command-from-spec]
   {"user" {"name" "Bob"}
    "ref"  {"commando-from" ["user"] "=>" ["get" "name"]}}))
;; => {"user" {"name" "Bob"}, "ref" "Bob"}


;; ┌─────────────────────────────────────────────────────┐
;; │ 7. DEBUGGING                                        │
;; └─────────────────────────────────────────────────────┘
;;
;; The `commando.debug` namespace helps visualize
;; what's happening inside an instruction.

(require '[commando.debug :as debug])

;; • execute-debug
;;
;; Runs an instruction and prints a visual representation.
;; Modes: `:tree`, `:table`, `:graph`, `:stats`

(debug/execute-debug
  [builtin/command-from-spec]
  {:a 1
   :b {:commando/from [:a]}
   :c {:commando/from [:b] :=> [:fn inc]}}
  :table)

;; or with multiple modes in a row

(debug/execute-debug
  [builtin/command-from-spec]
  {:a 1
   :b {:commando/from [:a]}
   :c {:commando/from [:b] :=> [:fn inc]}}
  [:instr-before :graph :instr-after :stats])

;; • execute-trace
;;
;; Shows timing/keys for all nested `execute` calls
;; (including recursive calls from macros/mutations).
;;
;; Here we define two arithmetic macros — :sum and :multiply —
;; and a :sum-of-products macro that uses them internally.
;; This creates three levels of nested execution:
;;
;;   Level 1: top instruction → :sum-of-products macro
;;   Level 2: :sum-of-products expands → two :multiply macros
;;   Level 3: each :multiply expands → :commando/fn *

(defmethod builtin/command-macro :sum [_ {:keys [a b]}]
  {:__title "Sum :a + :b"
   :commando/fn + :args [a b]})

(defmethod builtin/command-macro :multiply [_ {:keys [a b]}]
  {:commando/fn * :args [a b]})

(defmethod builtin/command-macro :sum-of-products [_ {:keys [a b c d]}]
  {:__title "Sum of products"
   :p1 {:commando/macro :multiply :a a :b b}
   :p2 {:commando/macro :multiply :a c :b d}
   :p3 {:commando/sum :sum :a {:commando/from [:p1]} :b {:commando/from [:p2]}}})

(:instruction
 (debug/execute-trace
   #(commando/execute
      [builtin/command-from-spec
       builtin/command-fn-spec
       builtin/command-macro-spec
       builtin/command-apply-spec]
      {:__title "Result Of Two"
       :result-1 {:commando/macro :sum-of-products  :a 2 :b 3 :c 4 :d 5}
       :result-2 {:commando/macro :sum-of-products  :a 2 :b 3 :c 4 :d 5}})))
;; => {:result 26}
;; because (2*3) + (4*5) = 6 + 20 = 26
;;
;; The trace output will show three nested execute calls:
;; 1. Top-level instruction  {:result ...}
;; 2. :sum-of-products       {:p1 ... :p2 ... :commando/apply ...}
;; 3. :multiply              {:commando/apply {:commando/fn * ...}}
;; 
;;   TIP: Use optional key `:__title` at the top-level instruction map
;;        to name step of execution in output. 

;; ┌─────────────────────────────────────────────────────┐
;; │ THAT'S IT!                                          │
;; └─────────────────────────────────────────────────────┘
;;
;; You now know the core building blocks:
;; • Instructions — maps with data + commands
;; • Six builtin commands: `:commando/from`, `:commando/fn`,
;;   `:commando/apply`, `:commando/context`,
;;   `:commando/mutation`, `:commando/macro`
;; • Drivers (`:=>`) for post-processing + pipelines
;; • Custom commands via CommandMapSpec
;;
;; See the README for the full reference. Happy building!
