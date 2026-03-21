;; ┌─────────────────────────────────────────────────────┐
;; │ Commando + Reitit                                   │
;; └─────────────────────────────────────────────────────┘
;;
;; Commando instructions are plain data — maps with
;; embedded commands. That means a frontend (or any HTTP
;; client) can send an instruction as a request body,
;; and the server evaluates it and returns the result.
;;
;; This walkthrough shows how to expose Commando as a
;; single POST endpoint behind Reitit + Ring, using
;; Transit for serialization.
;;
;; The pattern:
;;   Client sends   → {:instruction {...}}
;;   Server runs    → (commando/execute registry instruction)
;;   Server returns → {:status :ok, :instruction {...}}


;; ┌─────────────────────────────────────────────────────┐
;; │ 1. SETUP                                            │
;; └─────────────────────────────────────────────────────┘

(require '[commando.core               :as commando])
(require '[commando.commands.builtin   :as builtin])
(require '[reitit.http                 :as http])
(require '[reitit.interceptor.sieppari :as reitit-sieppari])
(require '[reitit.ring                 :as ring])
(require '[ring.middleware.transit     :as ring-transit])


;; ┌─────────────────────────────────────────────────────┐
;; │ 2. THE REGISTRY                                     │
;; └─────────────────────────────────────────────────────┘
;;
;; Define which commands the endpoint accepts. This is
;; your API surface — only commands in the registry can
;; be executed. Nothing else gets through.

(def api-registry
  (commando/registry-create
    [builtin/command-from-spec
     builtin/command-apply-spec
     builtin/command-fn-spec
     builtin/command-mutation-spec]))

;; You can have multiple registries for different
;; endpoints. A production app might look like:
;;
;;   /commando       → rest-api-registry (queries, mutations)
;;   /commando-admin → admin-registry (settings, system specific)
;;   /commando-debug → rest-api-registry + debug hooks
;;
;; Each endpoint exposes a different set of capabilities,
;; all through the same execution model.


;; ┌─────────────────────────────────────────────────────┐
;; │ 3. THE HANDLER                                      │
;; └─────────────────────────────────────────────────────┘
;;
;; The handler extracts the instruction from the request
;; body, executes it, and returns the status-map.
;;
;; Transit middleware (wrap-transit-body / wrap-transit-
;; response) handles serialization — the instruction
;; arrives as a Clojure map, keywords intact.

(defn- commando-handler [request]
  (if-let [instruction (:instruction (:body request))]
    (let [result (commando/execute api-registry instruction)]
      (if (commando/ok? result)
        {:status  200
         :headers {"Content-Type" "application/transit+json; charset=UTF-8"}
         :body    (select-keys result [:status :instruction :successes])}
        {:status  500
         :headers {"Content-Type" "application/transit+json; charset=UTF-8"}
         :body    (select-keys result [:status :errors :warnings :successes])}))
    {:status  400
     :headers {"Content-Type" "application/transit+json; charset=UTF-8"}
     :body    {:status :failed
               :errors [{:message "Instruction is empty"}]}}))

;; That's the entire backend. No controllers, no route
;; params, no manual validation per endpoint. The
;; instruction IS the request.


;; ┌─────────────────────────────────────────────────────┐
;; │ 4. WIRING IT INTO REITIT                            │
;; └─────────────────────────────────────────────────────┘
;;
;; One POST route. Transit middleware wraps the handler
;; so the client sends and receives Transit+JSON.

(def app
  (http/ring-handler
    (http/router
      [["/commando"
        {:post {:handler (-> commando-handler
                           ring-transit/wrap-transit-body
                           ring-transit/wrap-transit-response)}}]])
    (ring/create-default-handler)
    {:executor reitit-sieppari/executor}))

;; Start your server (Jetty, http-kit, etc.) with `app`
;; as the handler and you're done.


;; ┌─────────────────────────────────────────────────────┐
;; │ 5. CLIENT SIDE                                      │
;; └─────────────────────────────────────────────────────┘
;;
;; The client sends a Transit-encoded map with an
;; :instruction key. Here's what the request body
;; looks like (before Transit encoding):
;;
;;   {:instruction
;;    {:user-name "Alice"
;;     :greeting  {:commando/from [:user-name]}}}
;;
;; The server returns:
;;
;;   {:status      :ok
;;    :instruction {:user-name "Alice"
;;                  :greeting  "Alice"}}
;;
;; A more realistic example — the client asks the server
;; to save a user and return the result:
;;
;;   {:instruction
;;    {:user {:commando/mutation :save-user
;;            :name  "Alice"
;;            :email "alice@example.com"}
;;     :id   {:commando/from [:user] :=> [:get :id]}}}
;;
;; The server resolves the dependency chain, executes
;; the mutation, and returns the evaluated instruction.
;; The client never constructs SQL or calls REST
;; endpoints — it describes what it needs, and Commando
;; figures out the execution order.


;; ┌─────────────────────────────────────────────────────┐
;; │ 6. MULTIPLE REGISTRIES, MULTIPLE ENDPOINTS          │
;; └─────────────────────────────────────────────────────┘
;;
;; A registry defines what a client is allowed to do.
;; Different endpoints can expose different registries —
;; same execution model, different permissions.
;;
;; Example: a public API that only reads data, and an
;; admin API that can also write.

(def public-registry
  (commando/registry-create
    [builtin/command-from-spec
     builtin/command-apply-spec]))

(def admin-registry
  (commando/registry-create
    [builtin/command-from-spec
     builtin/command-apply-spec
     builtin/command-fn-spec
     builtin/command-mutation-spec]))

;; The handler is the same — only the registry changes.

(defn- make-commando-handler [registry]
  (fn [request]
    (if-let [instruction (:instruction (:body request))]
      (let [result (commando/execute registry instruction)]
        (if (commando/ok? result)
          {:status  200
           :headers {"Content-Type" "application/transit+json; charset=UTF-8"}
           :body    (select-keys result [:status :instruction :successes])}
          {:status  500
           :headers {"Content-Type" "application/transit+json; charset=UTF-8"}
           :body    (select-keys result [:status :errors :warnings :successes])}))
      {:status  400
       :headers {"Content-Type" "application/transit+json; charset=UTF-8"}
       :body    {:status :failed
                 :errors [{:message "Instruction is empty"}]}})))

(defn- wrap-transit [handler]
  (-> handler
    ring-transit/wrap-transit-body
    ring-transit/wrap-transit-response))

(def app-multi
  (http/ring-handler
    (http/router
      [["/api/query"
        {:post {:handler (wrap-transit (make-commando-handler public-registry))}}]
       ["/api/admin"
        {:post {:handler (wrap-transit (make-commando-handler admin-registry))}}]])
    (ring/create-default-handler)
    {:executor reitit-sieppari/executor}))

;; A client hitting /api/query can use :commando/from
;; and :commando/apply — read-only operations. If they
;; try :commando/mutation, Commando won't recognize it
;; because it's not in the public-registry.
;;
;; A client hitting /api/admin gets the full set —
;; mutations, functions, everything.
;;
;; The registry is the access control. No middleware
;; needed to filter operations — unregistered commands
;; are simply invisible.


;; ┌─────────────────────────────────────────────────────┐
;; │ 7. WHY THIS WORKS                                   │
;; └─────────────────────────────────────────────────────┘
;;
;; a) ONE ENDPOINT, INFINITE QUERIES
;;    The client doesn't hit /users, /orders, /reports.
;;    It sends an instruction to /commando. New mutations
;;    and queries are added server-side via `defmethod` —
;;    no new routes, no new controllers.
;;
;; b) THE REGISTRY IS YOUR API CONTRACT
;;    Only commands in the registry can execute. The
;;    client can't call arbitrary code — just the
;;    operations you explicitly allow.
;;
;; c) DEPENDENCIES RESOLVE AUTOMATICALLY
;;    The client says "I need :user, then :email depends
;;    on :user". Commando sorts the execution order.
;;    The client doesn't care which runs first.
;;
;; d) TRANSIT PRESERVES TYPES
;;    Keywords, UUIDs, dates — Transit keeps them intact.
;;    The instruction arrives as idiomatic Clojure data.
;;    No manual parsing, no string-to-keyword conversion.
;;
;; e) COMPOSABLE ON THE CLIENT
;;    Instructions are data. The frontend can merge
;;    partial instructions, build them conditionally,
;;    or cache and replay them. Try doing that with
;;    a traditional REST API.
