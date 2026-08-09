# 1.3.0

## Added

ADDED `:hook-command-guard-outer-fn` / `:hook-command-guard-inner-fn` / `:hook-command-guard-all-fn` config keys to `commando/execute` — a pre-execution reject gate for validating an Instruction's structure per-call, before any command runs. Existing hooks (`:hook-execute-start`/`:hook-execute-end`) are pure observers whose return value is discarded, so there was no way to reject an instruction up front. A guard fn has the shape of an internal pipeline step — `(fn [status-map]) => status-map` — and runs once as a new `step-guard-commands` step right after `step-find-commands`. Outer/inner/all scope the check to call depth, since config is inherited by nested `execute` calls (`:commando/macro`, `:commando/resolve`) and an outer-only check must not also reject the library's own internal nested calls.

When defining your own guard fn: to reject, return the status-map wrapped with `commando.impl.status-map/status-map-handle-error` — anything else (including the status-map as-is) lets execution continue.

Plain example:
```clojure
(commando/execute registry instruction
  {:hook-command-guard-outer-fn
   (fn [status-map]
     (if (allowed? (:instruction status-map))
       status-map
       (status-map/status-map-handle-error status-map {:message "instruction not allowed"})))})
```

ADDED `commando.utils` namespace, with `hook-reject-commands-fn` — a convenience helper meant to be called from inside a `:hook-command-guard-*-fn`. Walks the already-computed `:internal/cm-list` (no extra instruction traversal) and calls a `{:command-type :path :value} -> error-map-or-nil` predicate per found command; every found command is checked, so multiple violations all end up in `:errors`, not just the first.

ADDED `commando.core/steps-pipeline-default` / `execute-steps` / `with-execute-context` — `execute`'s pipeline exposed as data (a vector of `{:step-name :step-fn :step-assert}` maps) instead of a hardcoded `->` chain, for stopping and resuming execution at a chosen step (e.g. to inspect `:internal/cm-running-order` for a UI/debug tool before commands actually run). The individual pipeline steps (`step-use-registry`, `step-find-commands`, `step-guard-commands`, `step-build-deps-tree`, `step-sort-commands-by-deps`, `step-prepare-execution-status-map`, `step-execute-commands!`) are now public functions instead of private ones, all under the `step-*` naming convention.
- `execute-steps` throws if called outside `with-execute-context` (steps read `*execute-internals*`/`*execute-config*` and would otherwise silently fall back to defaults instead of failing loudly).
- Each step carries a `:step-assert` — `commando.core/step-assert-keys` throws if the status-map is missing keys the step needs, catching a miscomposed/reordered step subset. This is a wiring bug, not a data problem, so it's a thrown exception rather than a status-map `:errors` entry — `execute-steps` itself doesn't interpret `:step-assert`, so a custom step can throw whatever shape it wants.
- `execute` itself is now just `(execute-steps status-map (steps-pipeline-default registry))` wrapped in `with-execute-context` — no behavior change for existing callers.
- This opens the door to building your own execute pipelines on top of Commando's — stopping/resuming at a chosen step, or composing a custom chain of steps — without forking `execute`. See the README's "Flow Control (advanced)" section.

ADDED `command-quote-spec` in `commando.commands.builtin` — a new command type `:commando/quote` (string form `"commando-quote"`) that brings Lisp-style quasiquote semantics to instructions. A `:commando/quote` body is treated as **inert data**: commands inside it are NOT executed and the scanner stops descending — *except* inside `:commando/unquote` holes (string form `"commando-unquote"`), which ARE executed and whose results are substituted back into the body.
- `:commando/unquote` is pure syntax recognized only inside a quote — it is not a registered command and has no special meaning outside a quote.
- A nested `:commando/quote` stays fully inert (its wrapper is left untouched), so quotes can be nested safely.
- `:commando/from` and other commands inside an `:commando/unquote` resolve against the full instruction, so unquote holes can reference values outside the quote.
- A `:commando/from` pointing *at* a quote node receives the expanded (unquoted) body.

ADDED dependency mode `:quote` in `commando.impl.dependency` — internal mode used by `command-quote-spec`. Dependencies are collected from the quote sub-trie but are only visible through `:finding-commands-unquote-keys`; the scanner halts at `:finding-commands-skip-keys` (nested quotes). Supported public modes remain `:point`, `:all-inside`, `:none`.

# 1.2.0

## Breaking Changes

**BREAKING** `execute` accepts an optional third argument — a config map that replaces the old `binding`-based `*execute-config*` dynamic var for passing options. Users no longer need `(binding [utils/*execute-config* {...}] (execute ...))`.
- `(execute registry instruction)` — unchanged
- `(execute registry instruction {:error-data-string false})` — new opts map
- Config keys: `:error-data-string`, `:hook-execute-start`, `:hook-execute-end`
- Config is inherited by nested execute calls; inner calls can override specific keys.

**BREAKING** `execute-trace` signature changed from `(execute-trace exec-fn)` to `(execute-trace registry instruction)` / `(execute-trace registry instruction opts)`. No longer requires wrapping in a zero-arg function.

**BREAKING** Removed `:debug-result` configuration option. Internal structures (`:internal/cm-list`, `:internal/cm-dependency`, `:internal/cm-running-order`, `:internal/path-trie`, `:internal/cm-results`, `:internal/original-instruction`, `:registry`) are now **always** retained in the status-map. If you relied on stripped status-maps, dissoc internal keys yourself.

**BREAKING** Removed dependency modes `:all-inside-recur` and `:point-and-all-inside-recur`. Supported modes: `:point`, `:all-inside`, `:none`.

**BREAKING** Removed `print-stats`, `print-trace` (`print-deep-stats`) from `commando.impl.utils`. Replaced by `commando.debug` namespace.

**BREAKING** `registry_test.clj` renamed to `registry_test.cljc` (cross-platform).

## Added

ADDED `commando.debug` namespace — dedicated module for debug visualization:
- `execute-debug` — execute and visualize in one of six display modes: `:tree`, `:table`, `:graph`, `:stats`, `:instr-before` / `:instr-after`. Supports combining multiple modes via vector.
- `execute-trace` — trace all nested `commando/execute` calls with timing and structure.

ADDED `commando.impl.pathtrie` module — trie data structure for O(depth) command lookup by path. Built during the same traversal pass as command discovery, eliminating extra passes over the instruction tree.

ADDED new status-map keys always present after execution:
- `:internal/cm-results` — map `{CommandMapPath -> resolved-value}` with result of each command's `:apply` function.
- `:internal/path-trie` — nested trie for efficient command lookup by path.
- `:internal/original-instruction` — the original instruction before command evaluation.

ADDED `structural-command-type?` and `structural-command-types` in `commando.impl.registry` for detecting internal structural commands (`:instruction/_value`, `:instruction/_map`, `:instruction/_vec`).

## Performance

OPTIMIZED `find-commands` BFS traversal in `commando.impl.finding_commands`:
- Replaced vector-based queue with transient index-based queue (O(N) vs O(N^2) from subvec+into).
- Transient set for found-commands accumulation.
- Direct `enqueue-coll-children!` / `enqueue-command-children!` instead of intermediate mapv vectors.
- Path-trie built in the same pass — no separate traversal needed.

OPTIMIZED `execute-commands` in `commando.impl.executing`:
- Transient results map avoids N persistent map copies during execution loop.
- Index-based loop with `nth` instead of `rest`/`first` on remaining commands.

OPTIMIZED `build-dependency-graph` in `commando.impl.dependency`:
- Accepts pre-built path-trie from `find-commands` instead of rebuilding it.
- Transient accumulation for forward dependency map.
- `:all-inside` dependency resolution uses `reduce-kv` on trie subtree instead of dissoc+vals+keep+set chain.

OPTIMIZED `topological-sort` in `commando.impl.graph`:
- Transient maps during in-degree computation.
- Transient queue for sorted result accumulation.

OPTIMIZED `CommandMapPath` in `commando.impl.command_map`:
- Hash computed once at construction time and cached.
- `coll-starts-with?` uses indexed loop instead of lazy seq/take.

OPTIMIZED Malli validation in `commando.commands.builtin`:
- Pre-computed validators and explainers for each command spec.
- Cached coercer for status-map messages. Avoids re-creating schemas on every call.

## Fixed

FIXED `execute-single-command` in `commando.impl.executing` — guard for non-map `command-data` before calling `dissoc` on driver keys (`:=>`, `"=>`).

FIXED point dependency errors in `commando.impl.dependency` now include `:command-path`, `:path`, and `:command` in error data.

## Updated

UPDATED `resolve-relative-path` in `commando.impl.dependency` — refactored from reduce to recursive loop for clarity and correct early termination.

UPDATED `find-anchor-path` in `commando.impl.dependency` — refactored from reduce to loop.

UPDATED documentation — restructured `README.md` with comprehensive status-map documentation, improved navigation, "Managing the Registry" and "Debugging" sections. Moved doc files to `examples/` with runnable code examples.

UPDATED performance test alias from `:performance` to `:clj-test-perf-execute` in `deps.edn`.

UPDATED tests — split monolithic `core_test.cljc` into focused namespaces: `dependency_test.cljc`, `finding_commands_test.cljc`, `graph_test.cljc`, `pathtrie_test.cljc`. Added `debug_test.cljc`. Converted `registry_test` to `.cljc` for cross-platform support.


# 1.1.0

**BREAKING** REDESIGNED `:=` / `"="` with the new **Driver system** `:=>` / `"=>"`. The old keys are removed. Migration:
```clojure
:= :name              → :=> [:get :name],
:= [:a :b]            → :=> [:get-in [:a :b]],
:= (fn [result] ...)  → :=> [:fn (fn [result] ...)].
"=" "name"            → "=>" ["get" "name"],
```

Built-in drivers: `:identity` (default), `:default`, `:get`, `:get-in`, `:select-keys`, `:projection`, `:fn`. Supports pipelines (`[[:get :city] :uppercase]`) and custom drivers via `commando.impl.executing/command-driver` multimethod. See [README](./README.md) for details.

ADDED `command-context-spec` in `commando.commands.builtin`. A new command type `:commando/context` (string form: `"commando-context"`) that injects external reference data into instructions via closure. Call `(command-context-spec ctx-map)` to create a CommandMapSpec. Resolves with `{:mode :none}` — before all other commands, so `:commando/from` and `:commando/fn` can depend on context results.

ADDED option to modify already built registry with `registry-add` / `registry-remove` methods (identification by `:type` key).

RENAMED `create-registry` → `registry-create`. Old name removed.

REMOVED `build-compiler`. Compiler concept removed from the core functionality; optimizations for repeated `execute` calls will be introduced in a future version.

ADDED `print-trace` in `commando.impl.utils` — replaces `print-deep-stats` with an improved flamegraph that also shows per-node instruction keys and optional title. Add `:__title` or `"__title"` to any instruction's top level to annotate that node in the output. `print-deep-stats` is kept as a deprecated alias.

ADDED named anchor navigation for `:commando/from` paths. Declare an anchor with `"__anchor"` or `:__anchor` key in any instruction map, then reference it with `"@name"` as a path segment. The resolver walks up the tree and resolves to the nearest ancestor with that anchor name — independent of nesting depth. Anchors can be combined with existing `"../"` relative navigation in a single path.

UPDATED `resolve-relative-path` in `commando.impl.dependency` to accept an optional leading `instruction` argument and handle `"@anchor"` segments.

UPDATED `point-target-path` in `commando.impl.dependency` to pass the instruction into `resolve-relative-path`, enabling anchor resolution.

ADDED new keys to `commando.impl.utils/*execute-config*`. Added hooks keys
 - `:hook-execute-start` if not nil, call procedure at the start of `commando.core/execute` function.
 - `:hook-execute-end` if not nil, call procedure at the end of `commando.core/execute` function.

ADDED microsecs time measurement. All steps inside the `commando.core/execute` measure time. Every measurement adding to _status-map_ structure under `:stats` key.

UPDATED `status-map` structure. Was added two keys
 - `:uuid` autogenerated unique invocation identifier gotted for each `commando.core/execute` call
 - `:stats` contains vector of tuples like `["execute", 1085471, "1.085471ms"]` where `[<step-id>, <microsecs>, <formatted time>]`. Counts of steps depended from `*execute-config*` key `:debug-mode`.

UPDATED sort-commands-by-deps. Straightforward sets joining in base Kahn's algorithm was rewrited with in-degree counting optimization.

UPDATED build-deps-tree. Instead of searching dependency using the list of commandmaps by iterating across the list(O(n^2) in worst case), before starting to build a dependency graph, we quickly building a path-trie structure efficiently. This gave as fast way to resolve point/all-inside dependency only in O(n) time.

FIXED find-commands. StackOverflowException in case of long lists of dependencies in the one level.

REMOVED all `*-json-spec` builin commands were joined with it origin forms. Like `commando-from-json-spec` at now are handled by the original `commando-from-spec`, user just may use `:commando/from` either `"commando-from"` key to defining logic. Covering this special "string-based" instructions with tests.

UPDATED documentation about how to use commando DSL [with an JSON structure](./doc/json.md). 

ADDED to `commando.impl.utils` two helper functions: `print-stats` - to print status-map `:stats` key into output; `print-deep-stats` - printing the flamegraph basing on `:stats` of every internal `commando/execution`(very helpfull for debugging macroses or query_dsl) 

# 1.0.4
ADDED commando.commands.builtin/commando-macro-spec. The new type of command that allow to group instructions by its functionality and use it as a single command. Added Readme information about the macro.

ADDED documentation for commando.commands.builtin commands. Now each built-in command have explanation of its behavior and examples of usage.

UPDATED upgrade commando.commands.query-dsl. Function `resolve-query` was removed and replaced by `resolve-fn`, `resolve-instruction`, `resolve-instruction-qe` function called a **resolvers**. Explanations about the resolvers added to _docs/query-dsl.md_ file.

UPDATED error serialization. `commando.impl.utils` contains new way to serialize errors for CLJ/CLJS. Now all errors are serialized to map with keys: `:type`, `:class`, `:message`, `:data` (if exists) and `:stacktrace` (if exists), `:cause` (if exists). See `commando.impl.utils/serialize-exception` for more information. You can expand the error handlers using `serialize-exception-fn` multimethod (but for CLJ only).

ADDED tests for macro-spec, errors and query-dsl changes.

UPDATED README.md 'Debugging section' was replaced on 'Configuring Execution Behavior' which contains more detailed information how to modify execution behavior.

UPDATED dynamic variable *debug-mode* replaced by the `*execute-config*` which is a map that can contain multiple configuration options.

FIXED Removed `detach-instruction-commands` call from `commando.core/build-compiler`. In `commando.core/build-compiler`, the line that detached instruction commands from the registry was removed. This means the compiled registry now includes internal commands (_map, _value, _vector).

# 1.0.3
UPDATED behavior `:validate-params-fn`. If the function return anything except `true` it ment validation failure. If the function return data, they will be attached to returned error inside status map. Added tests.

FIXED align serialization of exeption for CLJ/CLJS

ADDED function normalization for :commando/fn, :commando/apply, :commando/from commands. In CLJ it will acept the symbols,vars,functions,keywords. In CLJS acceptable is only function and keywords.

FIXED QueryDSL. QueryExpression passing by :keys and :strs(for string Instruction keys)

# 1.0.2
FIXED bug issue with silent status-map after error execution.

# 1.0.1
Update for cljdocs

# 1.0.0
First version of commando released publicly
