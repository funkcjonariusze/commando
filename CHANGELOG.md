# 1.0.4
ADDED commando.commands.builtin/commando-macro-spec. The new type of command that allow to group instructions by its functionality and use it as a single command. Added Readme information about the macro.
ADDED documentation for commando.commands.builtin commands. Now each built-in command have explanation of its behavior and examples of usage.
UPDATED upgrade commando.commands.query-dsl. Function `resolve-query` was removed and replaced by `resolve-fn`, `resolve-instruction`, `resolve-instructions-qe` function called a **resolvers**. Explanations about the resolvers added to _docs/query-dsl.md_ file.
UPDATED error serialization. `commando.impl.utils` contains new way to serialize errors for CLJ/CLJS. Now all errors are serialized to map with keys: `:type`, `:class`, `:message`, `:data` (if exists) and `:stacktrace` (if exists), `:cause` (if exists). See `commando.impl.utils/serialize-error` for more information. You can expand the error handlers using `serialize-exception-fn` multimethod (but for CLJ only).
ADDED tests for macro-spec, errors and query-dsl changes.
UPDATED README.md 'Debugging section' was replaced on 'Configuring Execution Behavior' which contains more detailed information how to modify execution behavior.
UPDATED dynamic variable *debug-mode* replaced by the `*execute-config*` which is a map that can contain multiple configuration options.

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
