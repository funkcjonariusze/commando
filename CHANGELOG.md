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
