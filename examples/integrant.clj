;; ┌─────────────────────────────────────────────────────┐
;; │ Commando + Integrant                                │
;; └─────────────────────────────────────────────────────┘
;;
;; Integrant builds systems from a config map. Commando
;; builds config maps from instructions. Together they let
;; you describe infrastructure declaratively, with shared
;; settings, cross-references, and validation — things
;; that a plain Integrant map cannot do on its own.
;;
;; This walkthrough shows:
;; 1. The problem with vanilla Integrant configs
;; 2. Three custom commands that solve it
;; 3. A side-by-side comparison
;;
;; Evaluate each form in your REPL to follow along.


;; ┌─────────────────────────────────────────────────────┐
;; │ 1. SETUP                                            │
;; └─────────────────────────────────────────────────────┘

(require '[commando.core             :as commando])
(require '[commando.commands.builtin :as builtin])
(require '[integrant.core            :as ig])


;; ┌─────────────────────────────────────────────────────┐
;; │ 2. THE VANILLA INTEGRANT WAY                        │
;; └─────────────────────────────────────────────────────┘
;;
;; A typical Integrant config for an HTTP server backed
;; by a SQLite database looks like this:

(derive :plug/db          :plugin-sqlite/datasource)
(derive :plug/http-server :plugin-jetty-server/server-main)

(def config:integrant-native
  {:plug/db          {:dbtype "sqlite"
                      :dbname "storage.db"
                      :dir    "../../data"}
   :plug/http-server {:port    2500
                      :handler #'ring-handler
                      :db      (ig/ref :plug/db)}})

;; This works, but notice three things:
;;
;; 1. `derive` calls are side effects scattered outside
;;    the config — easy to forget when adding components.
;;
;; 2. The `:dir` value is duplicated if multiple components
;;    need the same project root.
;;
;; 3. `#ig/ref` is Integrant-specific syntax. If you want
;;    to validate, transform, or generate configs
;;    programmatically, you're working against the reader.


;; ┌─────────────────────────────────────────────────────┐
;; │ 3. THREE CUSTOM COMMANDS                            │
;; └─────────────────────────────────────────────────────┘
;;
;; We define three CommandMapSpecs that teach Commando
;; how to speak Integrant. Each one is a plain map —
;; no macros, no special syntax.

;; ─────────────────────────────────────────────────────
;;  :integrant/component
;; ─────────────────────────────────────────────────────
;;
;; Declares a component and its alias. The `:apply`
;; function calls `derive` automatically, so you never
;; need a separate side-effecting step.

(def command-integrant-component-spec
  {:type         :integrant/component
   :recognize-fn #(and (map? %) (contains? % :integrant/component))
   :apply        (fn [_ _ component]
                   (derive
                     (:integrant/component-alias component)
                     (:integrant/component component))
                   component)
   :dependencies {:mode :all-inside}})

;; ─────────────────────────────────────────────────────
;;  :integrant/from
;; ─────────────────────────────────────────────────────
;;
;; A typed reference to another component. Works like
;; `:commando/from`, but produces an `ig/ref` instead
;; of a raw value. It also validates that the target
;; is actually an `:integrant/component`.

(def command-integrant-from-spec
  {:type         :integrant/from
   :recognize-fn #(and (map? %) (contains? % :integrant/from))
   :apply        (fn [instruction _ {path :integrant/from :as cmd}]
                   (let [target (get-in instruction path)]
                     (if (contains? target :integrant/component-alias)
                       (ig/ref (:integrant/component-alias target))
                       (throw (ex-info
                                ":integrant/from must point to an :integrant/component"
                                cmd)))))
   :dependencies {:mode      :point
                  :point-key [:integrant/from]}})

;; ─────────────────────────────────────────────────────
;;  :integrant/system
;; ─────────────────────────────────────────────────────
;;
;; Collects resolved components into the final Integrant
;; config map — keyed by alias, with internal keys
;; stripped out.

(def command-integrant-system-spec
  {:type         :integrant/system
   :recognize-fn #(and (map? %) (contains? % :integrant/system))
   :apply        (fn [_ _ {components :integrant/system}]
                   (reduce
                     (fn [acc c]
                       (assoc acc
                         (:integrant/component-alias c)
                         (dissoc c :integrant/component
                                   :integrant/component-alias)))
                     {}
                     components))
   :dependencies {:mode :all-inside}})


;; ┌─────────────────────────────────────────────────────┐
;; │ 4. THE COMMANDO WAY                                 │
;; └─────────────────────────────────────────────────────┘
;;
;; Now the same system, described as a Commando instruction.
;; Shared settings live in one place. Components declare
;; themselves. References are validated at build time.

(def registry
  (commando/registry-create
    [builtin/command-from-spec
     command-integrant-component-spec
     command-integrant-from-spec
     command-integrant-system-spec]))

(def instruction
  {;; Shared settings — referenced by any component
   "settings" {:project-root "../../data"}

   ;; Database component
   "db"
   {"sqlite"
    {:integrant/component       :plugin-sqlite/datasource
     :integrant/component-alias :plug/db
     :dbtype "sqlite"
     :dbname "storage.db"
     :dir    {:commando/from ["settings" :project-root]}}}

   ;; HTTP server component
   "http-server"
   {:integrant/component       :plugin-jetty-server/server-main
    :integrant/component-alias :plug/http-server
    :port    2500
    :handler #'ring-handler
    :db      {:integrant/from ["db" "sqlite"]}}

   ;; Assemble the final Integrant config
   "integrant-config"
   {:integrant/system
    [{:commando/from ["db" "sqlite"]}
     {:commando/from ["http-server"]}]}})

;; Build it:

(def result (commando/execute registry instruction))

(commando/ok? result)
;; => true

(get-in result [:instruction "integrant-config"])
;; => {:plug/db          {:dbtype "sqlite", :dbname "storage.db",
;;                        :dir "../../data"}
;;     :plug/http-server {:port 2500, :handler #'ring-handler,
;;                        :db #ig/ref :plug/db}}
;;
;; That's a valid Integrant config — pass it to `ig/init`.


;; ┌─────────────────────────────────────────────────────┐
;; │ 5. WHY BOTHER?                                      │
;; └─────────────────────────────────────────────────────┘
;;
;; For two components, the vanilla approach is fine. The
;; Commando approach pays off when your system grows:
;;
;; a) SHARED SETTINGS
;;    `:commando/from` pulls values from a single source.
;;    Change `:project-root` once — every component sees
;;    the new value. No grep-and-replace.
;;
;; b) SELF-CONTAINED COMPONENTS
;;    Each component carries its own `:integrant/component`
;;    and alias. Adding a component means adding one map —
;;    no separate `derive` call, no risk of forgetting it.
;;
;; c) VALIDATED REFERENCES
;;    `:integrant/from` checks that the target is actually
;;    a component. A typo in the path fails at build time,
;;    not at runtime when `ig/init` tries to resolve a
;;    missing ref.
;;
;; d) COMPOSABLE
;;    Instructions are data. You can merge them, generate
;;    them, or store them in EDN/JSON files. Try doing
;;    that with `#ig/ref` reader literals and `derive`
;;    side effects.


;; ┌─────────────────────────────────────────────────────┐
;; │ 6. BONUS: ENVIRONMENT VARIABLES                     │
;; └─────────────────────────────────────────────────────┘
;;
;; Need to read env vars in your config? Define a command
;; for it — similar to how juxt/aero handles `#env`.

(def command-env-spec
  {:type         :env/get
   :recognize-fn #(and (map? %) (contains? % :env/get))
   :apply        (fn [_ _ {:keys [env/get default]}]
                   (or (System/getenv get) default))
   :dependencies {:mode :none}})

;; Now use it anywhere in the instruction:

(:instruction
 (commando/execute
   [command-env-spec]
   {:home {:env/get "HOME" :default "~/"}}))
;; => {:home "/home/alice"}


;; ┌─────────────────────────────────────────────────────┐
;; │ 7. PUTTING IT ALL TOGETHER                          │
;; └─────────────────────────────────────────────────────┘
;;
;; In a real application, the pattern looks like this:

(defn build-integrant-config
  "Takes a Commando instruction, returns a ready Integrant config."
  [instruction]
  (let [result (commando/execute registry instruction)]
    (if (commando/failed? result)
      (throw (ex-info "Failed to build Integrant config" result))
      (get-in result [:instruction "integrant-config"]))))

;; In your -main or dev namespace:
;;
;;   (ig/init
;;     (build-integrant-config instruction)
;;     [:plug/http-server])
;;
;; That's it. Commando builds the config. Integrant runs
;; the system. Each library does what it does best.
