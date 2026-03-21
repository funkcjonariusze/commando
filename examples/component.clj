;; ┌─────────────────────────────────────────────────────┐
;; │ Commando + Stuart Sierra's Component                │
;; └─────────────────────────────────────────────────────┘
;;
;; Component builds running systems from records and
;; dependency declarations. Aero reads tagged-literal
;; EDN configs with `#env`, `#profile`, `#ref`, etc.
;;
;; Together they solve a real problem: config values,
;; component constructors, and dependency wiring all
;; live in separate places. Commando unifies them —
;; config, deps, and assembly in one data structure.
;;
;; This walkthrough shows:
;; 1. Stub components you can run in the REPL
;; 2. The vanilla Component + Aero approach
;; 3. The same system built with Commando
;; 4. A full start/stop cycle
;;
;; Evaluate each form in your REPL to follow along.
;; Everything here is self-contained — no external deps
;; beyond Commando and Component.


;; ┌─────────────────────────────────────────────────────┐
;; │ 1. SETUP                                            │
;; └─────────────────────────────────────────────────────┘

(require '[commando.core                :as commando])
(require '[commando.commands.builtin    :as builtin])
(require '[com.stuartsierra.component   :as component])


;; ┌─────────────────────────────────────────────────────┐
;; │ 2. STUB COMPONENTS                                  │
;; └─────────────────────────────────────────────────────┘
;;
;; In a real app these would open connections and bind
;; ports. Here they just print and track their state.

;; ── Database ──────────────────────────────────────────

(defrecord Database [host port dbname
                     ;; managed state
                     connection]
  component/Lifecycle
  (start [this]
    (println (str ";; [start] Database " host ":" port "/" dbname))
    (assoc this :connection {:status :connected
                             :uri    (str "jdbc:sqlite://" host ":" port "/" dbname)}))
  (stop [this]
    (println ";; [stop]  Database")
    (assoc this :connection nil)))

(defn new-database [config]
  (map->Database config))

;; ── HTTP Server ───────────────────────────────────────

(defn stub-handler [request]
  {:status  200
   :headers {"Content-Type" "text/plain"}
   :body    "OK"})

(defrecord HttpServer [port handler
                       ;; injected dependency
                       database
                       ;; managed state
                       server]
  component/Lifecycle
  (start [this]
    (println (str ";; [start] HttpServer on port " port))
    (assoc this :server {:status  :running
                         :port    port
                         :handler handler}))
  (stop [this]
    (println (str ";; [stop]  HttpServer on port " port))
    (assoc this :server nil)))

(defn new-http-server [config]
  (map->HttpServer config))

;; ── Scheduler ─────────────────────────────────────────
;; A third component to show how dependencies scale.

(defrecord Scheduler [interval-ms
                      ;; injected
                      database
                      ;; managed
                      running?]
  component/Lifecycle
  (start [this]
    (println (str ";; [start] Scheduler every " interval-ms "ms"))
    (assoc this :running? true))
  (stop [this]
    (println ";; [stop]  Scheduler")
    (assoc this :running? false)))

(defn new-scheduler [config]
  (map->Scheduler config))


;; ┌─────────────────────────────────────────────────────┐
;; │ 3. THE VANILLA COMPONENT + AERO WAY                 │
;; └─────────────────────────────────────────────────────┘
;;
;; A typical Component system wires things up in code.
;; Config comes from Aero (or a plain map), constructors
;; are called by hand, and `using` declares deps.
;;
;; Imagine this Aero config.edn:
;;
;;   {:db   {:host #env DB_HOST
;;           :port #long #or [#env DB_PORT "5432"]
;;           :dbname #profile {:dev "dev.db" :prod "prod.db"}}
;;    :http {:port #long #or [#env PORT "3000"]}
;;    :scheduler {:interval-ms 5000}}
;;
;; Then in your code:

(defn create-system-vanilla
  "Build the system by hand — three separate concerns
  interleaved: config, construction, and wiring."
  [config]
  (component/system-map
    ;; 1) Config values scattered into constructors
    :database  (new-database (:db config))
    :http      (component/using
                 (new-http-server (assoc (:http config)
                                         :handler stub-handler))
                 ;; 2) Dependency wiring — separate from config
                 {:database :database})
    :scheduler (component/using
                 (new-scheduler (:scheduler config))
                 ;; 3) More wiring — easy to forget when adding
                 ;;    a new component
                 {:database :database})))

;; Try it with hardcoded values (in real code, Aero
;; would read these from config.edn + env vars):

(def config-vanilla
  {:db        {:host "localhost" :port 5432 :dbname "dev.db"}
   :http      {:port 3000}
   :scheduler {:interval-ms 5000}})

(def sys-vanilla (component/start (create-system-vanilla config-vanilla)))
;; => ;; [start] Database localhost:5432/dev.db
;; => ;; [start] HttpServer on port 3000
;; => ;; [start] Scheduler every 5000ms

(component/stop sys-vanilla)
;; => ;; [stop]  Scheduler
;; => ;; [stop]  HttpServer on port 3000
;; => ;; [stop]  Database

;; This works, but notice:
;;
;; 1. CONFIG + CONSTRUCTION + WIRING are three separate
;;    things. Adding a component means editing all three.
;;
;; 2. Aero's `#ref` can cross-reference config values,
;;    but it cannot reference *components*. Wiring is
;;    always in code.
;;
;; 3. Shared values (like the host) must be duplicated
;;    or extracted into a let-binding — there's no
;;    declarative "use the same value" mechanism.
;;
;; 4. The config file (EDN) and the system constructor
;;    (Clojure) must be kept in sync manually.


;; ┌─────────────────────────────────────────────────────┐
;; │ 4. CUSTOM COMMANDO COMMANDS                         │
;; └─────────────────────────────────────────────────────┘
;;
;; Three CommandMapSpecs that teach Commando how to
;; speak Component. Each is a plain map.

;; ─────────────────────────────────────────────────────
;;  :component/def
;; ─────────────────────────────────────────────────────
;;
;; Declares a component: its constructor, config,
;; and dependencies — all in one place.
;;
;; Example:
;;   {:component/def   {:constructor new-database
;;                       :using       {:database :database}}
;;    :host "localhost"
;;    :port 5432}
;;
;; The `:apply` function:
;; 1. Calls the constructor with the config (sans meta keys)
;; 2. Attaches `component/using` if `:using` is present

(def command-component-def-spec
  {:type         :component/def
   :recognize-fn #(and (map? %) (contains? % :component/def))
   :apply        (fn [_ _ component]
                   (let [{:keys [constructor using]} (:component/def component)
                         config (dissoc component :component/def)
                         instance (constructor config)]
                     (if using
                       (component/using instance using)
                       instance)))
   :dependencies {:mode :all-inside}})

;; ─────────────────────────────────────────────────────
;;  :component/system
;; ─────────────────────────────────────────────────────
;;
;; Collects resolved components into a
;; `component/system-map`. Each entry is a
;; `[keyword component-ref]` pair.
;;
;; Example:
;;   {:component/system
;;    [[:database  {:commando/from ["db"]}]
;;     [:http      {:commando/from ["http-server"]}]]}

(def command-component-system-spec
  {:type         :component/system
   :recognize-fn #(and (map? %) (contains? % :component/system))
   :apply        (fn [_ _ {entries :component/system}]
                   (apply component/system-map
                          (mapcat (fn [[k v]] [k v]) entries)))
   :dependencies {:mode :all-inside}})


;; ┌─────────────────────────────────────────────────────┐
;; │ 5. THE COMMANDO WAY                                 │
;; └─────────────────────────────────────────────────────┘
;;
;; The same system — config, construction, wiring, and
;; assembly — all in one data structure. Compare this
;; with section 3 above.

(def registry
  (commando/registry-create
    [builtin/command-from-spec
     command-component-def-spec
     command-component-system-spec]))

(def instruction
  {;; ── Shared settings ────────────────────────────────
   ;; Referenced by any component via :commando/from.
   ;; Change once — every component sees the update.
   "settings"
   {:host "localhost"
    :port 5432
    :dbname "dev.db"}

   ;; ── Database ───────────────────────────────────────
   ;; Config + constructor + deps: one self-contained map.
   "db"
   {:component/def {:constructor new-database}
    :host   {:commando/from ["settings" :host]}
    :port   {:commando/from ["settings" :port]}
    :dbname {:commando/from ["settings" :dbname]}}

   ;; ── HTTP Server ────────────────────────────────────
   ;; `:using` declares that this component needs :database
   ;; injected from the system — same as `component/using`.
   "http-server"
   {:component/def {:constructor new-http-server
                    :using       {:database :database}}
    :port    3000
    :handler stub-handler}

   ;; ── Scheduler ──────────────────────────────────────
   "scheduler"
   {:component/def {:constructor new-scheduler
                    :using       {:database :database}}
    :interval-ms 5000}

   ;; ── Assemble the system ────────────────────────────
   ;; Produces a `component/system-map` ready to start.
   "system"
   {:component/system
    [[:database  {:commando/from ["db"]}]
     [:http      {:commando/from ["http-server"]}]
     [:scheduler {:commando/from ["scheduler"]}]]}})


;; Build it:

(def result (commando/execute registry instruction))

(commando/ok? result)
;; => true

(def sys (get-in result [:instruction "system"]))

sys
;; => #<SystemMap {:database #app.Database{...}
;;                 :http     #app.HttpServer{...}
;;                 :scheduler #app.Scheduler{...}}>


;; ┌─────────────────────────────────────────────────────┐
;; │ 6. START & STOP                                     │
;; └─────────────────────────────────────────────────────┘

(def running (component/start sys))
;; => ;; [start] Database localhost:5432/dev.db
;; => ;; [start] HttpServer on port 3000
;; => ;; [start] Scheduler every 5000ms

;; Inspect the running system:
(get-in running [:database])
;; => #<Database>{:host ...}
(get-in running [:database :connection])
;; => {:status :connected, :uri "jdbc:sqlite://localhost:5432/dev.db"}

(get-in running [:http])
;; => #<HttpServer>{:port ...}
(get-in running [:http :server])
;; => {:status :running, :port 3000, :handler #function}

(component/stop running)
;; => ;; [stop]  Scheduler
;; => ;; [stop]  HttpServer on port 3000
;; => ;; [stop]  Database


;; ┌─────────────────────────────────────────────────────┐
;; │ 7. SIDE-BY-SIDE COMPARISON                          │
;; └─────────────────────────────────────────────────────┘
;;
;; VANILLA (Component + Aero)   │ COMMANDO
;; ─────────────────────────────│──────────────────
;; config.edn (Aero)            │ instruction map
;;   + read-config              │   (plain data)
;;   + #env, #profile, #ref     │        
;; ─────────────────────────────│──────────────────
;; create-system fn             │ :component/system
;;   (new-database (:db cfg))   │   command
;;   (component/using ...)      │      
;; ─────────────────────────────│──────────────────
;; 3 files / places to edit     │ 1 instruction
;; when adding a component      │ to add a component
;; ─────────────────────────────│──────────────────
;; #ref only crosses config,    │ :commando/from
;; not into wiring or code      │ crosses everything
;; ─────────────────────────────│──────────────────
;; Aero tags are reader macros —│ Commands are data —
;; extensible but EDN-only      │ work in EDN, JSON,
;;                              │ Transit, or code


;; ┌─────────────────────────────────────────────────────┐
;; │ 8. BONUS: REPLACING AERO'S #env AND #profile        │
;; └─────────────────────────────────────────────────────┘
;;
;; Aero's most-used tags are `#env` and `#profile`.
;; Commando can replicate them as commands, making
;; your config fully portable (JSON, Transit, etc.)
;; without reader macro dependencies.

;; ── :env/get — read an environment variable ───────────

(def command-env-spec
  {:type         :env/get
   :recognize-fn #(and (map? %) (contains? % :env/get))
   :apply        (fn [_ _ {:keys [env/get default]}]
                   (or (System/getenv get) default))
   :dependencies {:mode :none}})

;; ── :config/profile — switch on active profile ────────

(def command-profile-spec
  {:type         :config/profile
   :recognize-fn #(and (map? %) (contains? % :config/profile))
   :apply        (fn [instruction _ {:keys [config/profile]}]
                   (let [active (get-in instruction ["settings" :profile])]
                     (or (get profile active)
                         (get profile :default))))
   :dependencies {:mode :none}})

;; Now your instruction can read env vars and switch
;; on profiles — just like Aero, but as plain data:

(def env-registry
  (commando/registry-create
    [builtin/command-from-spec
     command-env-spec
     command-profile-spec]))

(:instruction
 (commando/execute
   env-registry
   {"settings" {:profile :dev}
    "config"   {:db-host {:env/get "DB_HOST" :default "localhost"}
                :db-port {:config/profile {:dev     5432
                                           :prod    5433
                                           :default 5000}}}}))
;; => {"settings" {:profile :dev}
;;     "config"   {:db-host "localhost"
;;                 :db-port 5432}}


;; ┌─────────────────────────────────────────────────────┐
;; │ 9. PUTTING IT ALL TOGETHER                          │
;; └─────────────────────────────────────────────────────┘
;;
;; In a real application:

(defn build-system
  "Takes a Commando instruction, returns a Component system
  ready to start."
  [instruction]
  (let [result (commando/execute registry instruction)]
    (if (commando/failed? result)
      (throw (ex-info "Failed to build system" result))
      (get-in result [:instruction "system"]))))

;; Usage:
;;
;;   (def system (component/start (build-system instruction)))
;;   ;; => ;; [start] Database localhost:5432/dev.db
;;   ;; => ;; [start] HttpServer on port 3000
;;   ;; => ;; [start] Scheduler every 5000ms
;;
;;   (component/stop system)
;;
;; One instruction. One place to edit. Commando resolves
;; the config. Component runs the system.
