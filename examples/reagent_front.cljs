;; ┌─────────────────────────────────────────────────────┐
;; │ Commando + Reagent — Frontend Patterns              │
;; └─────────────────────────────────────────────────────┘
;;
;; Commando instructions are plain data — maps with embedded
;; commands. On the frontend this gives you two powers:
;;
;;   1. LOCAL execution — run instructions in the browser
;;      for validation, derived state, and reactive UI.
;;
;;   2. REMOTE execution — send instructions to the server
;;      via Transit POST and merge the result into state.
;;
;; Both paths share the same data format.
;; This file shows how to use each one with Reagent.


;; ┌─────────────────────────────────────────────────────┐
;; │ 1. SETUP                                            │
;; └─────────────────────────────────────────────────────┘

(ns examples.reagent-front
  (:require
   [commando.core             :as commando]
   [commando.commands.builtin :as builtin]
   [reagent.core              :as r]))


;; ┌─────────────────────────────────────────────────────┐
;; │ 2. THE IDEA                                         │
;; └─────────────────────────────────────────────────────┘
;;
;; A Reagent atom holds mutable state.
;; A Commando instruction describes what to compute from
;; that state — validation, derived values, predicates.
;;
;; When the atom changes, the instruction re-executes and
;; the UI reacts. All logic is data, not imperative code.
;;
;;   ┌───────────┐    ┌─────────────────┐    ┌────────┐
;;   │ r/atom    │───►│ instruction     │───►│ UI     │
;;   │ {:name _} │    │ {:valid? ...}   │    │ reacts │
;;   └───────────┘    └─────────────────┘    └────────┘
;;
;; The instruction sits between raw state and the UI.
;; It replaces scattered `let` bindings, inline validators,
;; and ad-hoc derived-state functions with a single
;; declarative pipeline.


;; ┌─────────────────────────────────────────────────────┐
;; │ 3. LOCAL EXECUTION — reactive form                  │
;; └─────────────────────────────────────────────────────┘
;;
;; `commando/execute` runs entirely in the browser.
;; Wrap it in `r/track` so Reagent re-evaluates it
;; whenever the atom changes.

(defn form-component []
  (let [state (r/atom {:car-model "" :car-specs ""})

        ;; The instruction. Every key is either plain data
        ;; or a command that Commando recognizes and evaluates.
        result
        (r/track
          (fn []
            (commando/execute
              [builtin/command-mutation-spec
               builtin/command-from-spec
               builtin/command-fn-spec]
              {;; Embed current state as a plain-data key.
               :state @state

               ;; Validate :car-model — mutation returns error list.
               :errors-model
               {:commando/mutation :ui/validate
                :value {:commando/from [:state :car-model]}
                :validators
                [(fn [v] (when (empty? v) "Enter car model"))
                 (fn [v] (when-not (re-matches #"(mazda|honda).*" v)
                           "Only mazda or honda"))]}

               ;; Validate :car-specs.
               :errors-specs
               {:commando/mutation :ui/validate
                :value {:commando/from [:state :car-specs]}
                :validators
                [(fn [v] (when (empty? v) "Enter specs"))]}

               ;; Derive a boolean from both validations.
               :valid?
               {:commando/fn
                (fn [e1 e2] (and (empty? e1) (empty? e2)))
                :args [{:commando/from [:errors-model]}
                       {:commando/from [:errors-specs]}]}})))

        ;; Helper — read a key from the evaluated instruction.
        get-val (fn [k] (get-in @result [:instruction k]))]

    ;; Render function — re-runs when `result` changes.
    (fn []
      [:div
       [:div "Car model"
        [:input {:on-change #(swap! state assoc :car-model
                               (.. % -target -value))}]]
       (for [msg (get-val :errors-model)]
         [:span {:style {:color "red"}} msg])

       [:div "Car specs"
        [:input {:on-change #(swap! state assoc :car-specs
                               (.. % -target -value))}]]
       (for [msg (get-val :errors-specs)]
         [:span {:style {:color "red"}} msg])

       [:button {:disabled (not (get-val :valid?))}
        "Submit"]])))

;; ─────────────────────────────────────────────────────
;;  The :ui/validate mutation
;; ─────────────────────────────────────────────────────
;;
;; Mutations are defined with `defmethod`. This one runs
;; a list of validator functions and collects error strings.

(defmethod builtin/command-mutation :ui/validate
  [_ {:keys [value validators]}]
  (not-empty
    (into []
      (keep (fn [validator] (validator value)))
      validators)))

;; NOTE: The `:status` returned by `commando/execute` can
;; be `:failed` — but that signals a structural problem
;; (missing dependency, broken command), not a user error.
;; For user-facing validation, read instruction keys like
;; `:errors-model` above. Don't branch on `:status` in UI.


;; ┌─────────────────────────────────────────────────────┐
;; │ 4. REMOTE EXECUTION — server as the engine          │
;; └─────────────────────────────────────────────────────┘
;;
;; The same instruction format works over HTTP. The client
;; builds an instruction, POSTs it as Transit to a single
;; `/commando` endpoint, and the server evaluates it
;; against its registry (resolvers, mutations, macros).
;;
;; The server returns a status-map:
;;
;;   {:status :ok, :instruction {...evaluated...}}
;;
;; The client takes `:instruction` from the response
;; and merges it into its local state.
;;
;; ─────────────────────────────────────────────────────
;;  Sending an instruction
;; ─────────────────────────────────────────────────────
;;
;; A minimal Transit POST looks like this:

(comment
  ;; Using cljs-ajax:
  (ajax/POST "/commando"
    {:format          (ajax/transit-request-format {})
     :response-format (ajax/transit-response-format {})
     :params          {:instruction
                       {:companies
                        {:commando/resolve :Company
                         :where [:= :active true]
                         :QueryExpression [:id :name :tax-number]}}}
     :handler         (fn [result]
                        (when (commando/ok? result)
                          ;; (:instruction result) is the evaluated map
                          (reset! app-state (:instruction result))))}))

;; The instruction can mix queries, mutations, and
;; dependencies — just like locally. The server resolves
;; the graph and returns everything at once.
;;
;;   {:instruction
;;    {:user      {:commando/mutation :save-user
;;                 :name "Alice" :email "a@b.com"}
;;     :send-mail {:commando/mutation :send-greeting-mail
;;                 :user {:commando/from [:user]}}
;;     :status    {:commando/from [:user] :=> [:get :status]}}}
;;
;; One POST, one response, no REST choreography.


;; ┌─────────────────────────────────────────────────────┐
;; │ 5. STATE MANAGEMENT PATTERN                         │
;; └─────────────────────────────────────────────────────┘
;;
;; A practical pattern for Reagent components that talk
;; to the server:
;;
;;   atom   + instruction  → POST /commando  → merge result
;;
;; The idea: component owns a `r/atom`. A helper function
;; sends an instruction to the server and deep-merges the
;; response back into the atom.
;;
;; ─────────────────────────────────────────────────────
;;  mutate-http — send instruction, merge result
;; ─────────────────────────────────────────────────────

(defn mutate-http
  "POST instruction to /commando. On success, deep-merge
   the evaluated instruction into the atom."
  [state-atom instruction]
  ;; (ajax/POST "/commando" ...)
  ;; on-success: (swap! state-atom deep-merge (:instruction result))
  )

;; ─────────────────────────────────────────────────────
;;  mutate-local — execute locally, merge result
;; ─────────────────────────────────────────────────────

(defn mutate-local
  "Execute instruction in the browser with the given
   registry and merge the result into the atom."
  [state-atom registry instruction]
  (let [result (commando/execute registry instruction)]
    (when (commando/ok? result)
      (swap! state-atom merge (:instruction result)))))

;; ─────────────────────────────────────────────────────
;;  Usage in a component
;; ─────────────────────────────────────────────────────
;;
;; Both functions share the same contract:
;;   (mutate-* atom instruction) → atom is updated
;;
;; The component doesn't care whether the instruction
;; ran locally or on the server — it just reads the atom.

(defn search-component []
  (let [state (r/atom {:loading false
                        :results []})]
    (fn []
      [:div
       [:input
        {:placeholder "Search..."
         :on-change
         (fn [e]
           ;; Build an instruction and send it to the server.
           ;; The server resolves :Company and returns data.
           ;; mutate-http merges {:results [...]} into the atom.
           (mutate-local state {:loading true})
           (mutate-http state
             {:results
              {:commando/mutation :CompanyFindByName
               :searching-string (.. e -target -value)}
              :loading false}))}]

       (when (:loading @state)
         [:span "Loading..."])

       (for [company (:results @state)]
         [:div {:key (:id company)} (:name company)])])))

;; This pattern scales:
;;
;; • Add debounce for frequent updates:
;;     (def search-debounced (debounce mutate-http 200))
;;
;; • Add :on-error callback for error handling.
;;
;; • Use mutate-local for client-only logic (validation,
;;   formatting) and mutate-http for server queries.
;;
;; • The atom is the single source of truth. The instruction
;;   is just a recipe for updating it.


;; ┌─────────────────────────────────────────────────────┐
;; │ 6. LOCAL + REMOTE — mixing both                     │
;; └─────────────────────────────────────────────────────┘
;;
;; A common pattern: validate locally, submit remotely.
;; Local execution is instant, server calls are async.

(comment
  ;; On every keystroke — validate locally:
  (mutate-local state
    [builtin/command-fn-spec]
    {:valid? {:commando/fn #(> (count %) 2)
              :args [(:search-text @state)]}})

  ;; On submit — send to server:
  (when (:valid? @state)
    (mutate-http state
      {:result {:commando/mutation :search
                :query (:search-text @state)}})))

;; The instruction format is the same in both cases.
;; What changes is where it executes and what commands
;; are available (the registry).


;; ┌─────────────────────────────────────────────────────┐
;; │ SUMMARY                                             │
;; └─────────────────────────────────────────────────────┘
;;
;; • Instructions are data — build them from component
;;   state, merge results back into state.
;;
;; • LOCAL: `commando/execute` in `r/track` for reactive
;;   derived state (validation, computed fields).
;;
;; • REMOTE: POST instruction to `/commando`, get back
;;   evaluated map, merge into atom.
;;
;; • `mutate-local` and `mutate-http` are the two
;;   fundamental operations. Both take an atom and an
;;   instruction, both update the atom with the result.
;;
;; • The server registry controls what commands are
;;   available — it's the API surface. The client just
;;   describes what it needs.
