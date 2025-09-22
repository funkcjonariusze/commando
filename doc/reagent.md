# Using Commando with Reagent: Best Practices and Patterns

This guide demonstrates how to integrate [Commando](../README.md) with [Reagent](https://reagent-project.github.io/), using a practical form component example for managing car data.

## Classic Reagent Form Example

Let's start with a typical Reagent form containing two fields for describing a car (e.g., Mazda MX-5). Typically, we store form state in a `reagent/atom`:

```clojure
(defn form-component []
  (let [state (reagent.core/atom {:car-model ""
                                  :car-specs ""})]
    (fn []
      [:div
       [:div "Car model"
        [:input {:defaultValue (:car-model @state)
                 :on-change #(swap! state assoc :car-model (.. % -target -value))}]]
       [:div "Car Specs"
        [:input {:defaultValue (:car-specs @state)
                 :on-change #(swap! state assoc :car-specs (.. % -target -value))}]]
       [:input {:type "button"
                :value "submit"
                :on-click #(js/console.log @state)}]])))
```

This is a standard Reagent controlled form. The state atom holds both fields. All logic—validation, transformation, etc. It typically implemented as separate functions or inline handlers.

---

## Bringing Commando into the Form

Usually, the next step is to create functions for validation, normalization, and data processing before submitting. The key idea behind Commando is to "soak up" all this logic into an **Instruction** — a declarative data structure representing dependencies and transformations.

With Commando, we pass the state atom directly into the instruction. All dependencies and transformations are transparent, and changes in state automatically trigger reactive updates.

```clojure
(require '[commando.core :as commando])
(require '[commando.commands.builtin :as commands-builtin])

(defn form-component []
  (let [state (reagent.core/atom {:car-model ""
                                  :car-specs ""})
        state-instruction
        (reagent.core/track
          (fn []
            (commando/execute
              [commands-builtin/command-mutation-spec
               commands-builtin/command-from-spec
               commands-builtin/command-fn-spec]
              {:state @state
               :validation-car-model
               {:commando/mutation :ui/validate
                :value {:commando/from [:state :car-model]}
                :validators
                [(fn [v]
                   (when (empty? v)
                     "Enter car model name"))
                 (fn [v]
                   (when-not (re-matches #"(suzuki|mazda|honda|hyundai).*" v)
                     (str "Enter model for suzuki, mazda, honda, hyundai: " v)))
                 ]}
               :validation-car-specs
               {:commando/mutation :ui/validate
                :value {:commando/from [:state :car-specs]}
                :validators [(fn [v]
                               (when (empty? v) "Enter car specs"))]}
               :validated?
               {:commando/fn #(not (boolean (not-empty (concat %1 %2))))
                :args [{:commando/from [:validation-car-model]}
                       {:commando/from [:validation-car-specs]}]}
               :on-change-event
               {:commando/fn (fn [validated? data]
                               (when validated?
                                 (js/console.log data)))
                :args [{:commando/from [:validated?]}
                       {:commando/from [:state]}]}})))
        get-instruction-value (fn [kvs] (reagent.core/track (fn [] (get-in @state-instruction (into [:instruction] kvs)))))
        set-value (fn [k v] (swap! state assoc k v))]
    (fn []
      [:div
       [:div
        "Car model"
        [:input {:defaultValue (:car-model @state)
                 :on-change #(set-value :car-model (.. % -target -value))}]]
        (for [error-string @(get-instruction-value [:validation-car-model])]
          [:span {:style {:color "darkred" :margin-left "10px"}} error-string])

       [:div
        "Car Specs"
        [:input {:defaultValue (:car-specs @state)
                 :on-change #(set-value :car-specs (.. % -target -value))}]]
        (for [error-string @(get-instruction-value [:validation-car-specs])]
          [:span {:style {:color "darkred" :margin-left "10px"}} error-string])

       [:input {:disabled (not @(get-instruction-value [:validated?]))
                :type "button"
                :on-click #(js/window.alert (js/JSON.stringify (clj->js @(get-instruction-value [:state]))))
                :value "submit"}]
       [:pre
        (with-out-str
          (cljs.pprint/pprint
            (:instruction @state-instruction)))]
      ])))
	  
(defmethod commands-builtin/command-mutation :ui/validate [_ {:keys [value validators]}]
  ;; The validator returns a list of error messages or nil.
  (not-empty
    (reduce
      (fn [acc validator]
        (if-let [msg (validator value)]
          (conj acc msg)
          acc))
      []
      validators)))	  
```

**All dependencies** (validation results, field values, etc.) are described as data, making it easy to compose and trace. Command results (like `:validated?`) drive UI state directly (e.g., disabling the submit button).
 The instruction is a fully declarative pipeline. Changing business logic or validation rules. Is a matter of editing data, not code.

## Why Is This Powerful?

- **Abstraction and Reuse:** Validation and state logic are data-driven and can be reused for any number of fields.
- **UI Control:** The result of validation (`:validated?`) directly controls UI state.
- **Transparency:** All dependencies are visible in the instruction — no hidden wiring.

## Front-End Optimizations

In the pattern above, every state change triggers a full re-execution of the instruction, including parsing, dependency analysis, and command evaluation. This is fine for small forms, but for larger instructions or frequent updates, it can be inefficient.

**Commando provides a compilation mechanism:** If your instruction structure is stable, you can "compile" it once, then re-execute only the final evaluation step as state changes.

```clojure
(def form-instruction-compiler
  (commando/build-compiler
    [commands-builtin/command-mutation-spec
     commands-builtin/command-from-spec
     commands-builtin/command-fn-spec]
    {:state {:car-model nil :car-specs nil}
     :validation-car-model
     {:commando/mutation :ui/validate
      :value {:commando/from [:state :car-model]}
      :validators ...}
     ...}))
```

You must provide default values for all fields referenced by `:commando/from`. Otherwise, execution will fail if a key command pointing is missing(i.e. `:commando/from [:state :car-model]`need to be founded even if the value is nil)

Now, in your component, you only need to update the state and re-execute the compiled instruction:

```clojure
(defn form-component []
  (let [state (reagent.core/atom {:car-model ""
                                  :car-specs ""})
        state-instruction
        (reagent.core/track
          (fn []
            (commando/execute
              form-instruction-compiler
              {:state @state
               :validation-car-model
               {:commando/mutation :ui/validate
                :value {:commando/from [:state :car-model]}
                :validators ...}
               ...})))]
    (fn []
      [:div ...])))
```

**Result:** Evaluation time is now much faster(because it practicaly linear) for large instruction graphs.

## Important Notes

- The status-map returned by `commando/execute` can have `:status :failed`. This indicates a *problem with the instruction structure*, not a user or business error. UI code should not directly react to these errors, just as you wouldn't use exceptions for routine validation.

Comment: Use validation results inside the instruction to drive UI, not the error status of Commando itself.

## Summary

**Commando** allows you to manage all form logic, validation, and state transformation declaratively, making your UI code simpler, more maintainable, and easier to reason about.

- Describe all logic as data (instructions), not as imperative code.
- Use compiled instructions for performance when structure is stable.
- Reactivity, validation, and business logic are all unified in a single pipeline.
- If you have complex forms, multi-step wizards, or need to synchronize state with APIs, Commando's data-driven approach can help you scale up with minimal boilerplate.

